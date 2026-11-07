package org.kushare.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.net.Inet4Address
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "KushareService"
private const val FOREGROUND_ID = 1

/** Puerto fijo del enlace (como en el escritorio); si está ocupado se usa otro. */
private const val LINK_PORT = 47816

/** Duración de una búsqueda de dispositivos pedida por el usuario. */
private const val SCAN_DURATION_MS = 30_000L

/** Reintentos de reconexión por Bluetooth sin eventos: de 1 a 15 minutos. */
private const val BT_RETRY_MIN_MS = 60_000L
private const val BT_RETRY_MAX_MS = 15 * 60_000L

/**
 * Servicio en primer plano de kushare. Solo corre si el usuario lo tiene
 * encendido ([KushareSwitch]); dentro, el **enlace** (escucha TCP/RFCOMM,
 * anuncio mDNS, reconexión, notificaciones) se abre y se cierra según haya
 * Bluetooth o Wi-Fi:
 *
 * - **En pausa** (Bluetooth apagado y sin Wi-Fi): el enlace está cerrado y
 *   el servicio solo espera el evento del sistema para reanudar; no gasta
 *   CPU ni radio.
 * - **Activo**: pensado para un enlace Bluetooth siempre abierto con poco
 *   consumo: sin escaneo continuo (solo [ACTION_SCAN], 30 s), reconexión
 *   por eventos (ACL conectado, Bluetooth encendido, vuelve el Wi-Fi) con un
 *   respaldo de 1 a 15 min, y keepalive por Bluetooth de minutos.
 */
class KushareService : Service() {
    companion object {
        const val ACTION_SCAN = "org.kushare.app.SCAN"

        @Volatile
        private var instance: KushareService? = null

        /** Cambió el modo de conexión: volver a anunciar (o retirar) la Wi-Fi al PC. */
        fun onLinkModeChanged() {
            instance?.let { it.serviceScope.launch { it.publishLan() } }
        }

        fun start(context: Context) {
            if (!KushareSwitch.isEnabled(context)) return
            runCatching {
                context.startForegroundService(Intent(context, KushareService::class.java))
            }.onFailure { Log.w(TAG, "no se pudo arrancar el servicio", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KushareService::class.java))
        }

        /** Busca dispositivos kushare en la red durante unos segundos. */
        fun scan(context: Context) {
            if (!KushareSwitch.isEnabled(context)) return
            runCatching {
                context.startForegroundService(
                    Intent(context, KushareService::class.java).setAction(ACTION_SCAN),
                )
            }.onFailure { Log.w(TAG, "no se pudo iniciar la búsqueda", it) }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Tareas del enlace; se cancela entera al pausar o apagar. */
    private var linkScope: CoroutineScope? = null
    private lateinit var nsd: LinkNsd
    private var btServer: BluetoothServerSocket? = null
    private var transferServer: BluetoothServerSocket? = null
    private var scanJob: Job? = null

    /** Avisos de Bluetooth (sin acumular): uno por bucle, para que ninguno se pierda el aviso. */
    private val btWakeReconnect = Channel<Unit>(Channel.CONFLATED)
    private val btWakeAccept = Channel<Unit>(Channel.CONFLATED)

    private fun wakeBluetooth() {
        btWakeReconnect.trySend(Unit)
        btWakeAccept.trySend(Unit)
    }

    private val bluetoothEvents =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> wakeBluetooth()
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                        if (state == BluetoothAdapter.STATE_ON || state == BluetoothAdapter.STATE_OFF) {
                            onRadiosChanged()
                        }
                        if (state == BluetoothAdapter.STATE_ON) wakeBluetooth()
                    }
                }
            }
        }

    /** Red Wi-Fi actual (para decirle al PC dónde abrir el enlace de datos). */
    @Volatile
    private var wifiNetwork: Network? = null

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wifiNetwork = network
                serviceScope.launch {
                    onRadiosChanged()
                    publishLan()
                    KushareLink.service?.wakeReconnect()
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                wifiNetwork = network
                serviceScope.launch { publishLan() }
            }

            override fun onLost(network: Network) {
                if (wifiNetwork == network) wifiNetwork = null
                serviceScope.launch {
                    onRadiosChanged()
                    publishLan()
                }
            }
        }

    /**
     * Modo híbrido: dice al PC (por el enlace de control, normalmente
     * Bluetooth) las IPv4 de la Wi-Fi y el puerto del enlace, para que abra
     * ahí un enlace de datos solo cuando haga falta. En "Solo Bluetooth" o
     * sin Wi-Fi, una lista vacía: todo sigue por Bluetooth.
     */
    private fun publishLan() {
        val link = KushareLink.service ?: return
        val network = wifiNetwork
        val addrs =
            if (network == null || !KushareSwitch.isHybrid(this)) {
                emptyList()
            } else {
                runCatching {
                    getSystemService(ConnectivityManager::class.java)
                        .getLinkProperties(network)
                        ?.linkAddresses
                        ?.map { it.address }
                        ?.filterIsInstance<Inet4Address>()
                        ?.mapNotNull { it.hostAddress }
                }.getOrNull().orEmpty()
            }
        runCatching { link.publishLanAddress(addrs, LINK_PORT.toUShort()) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        nsd = LinkNsd(this)
        startForeground(paused = false)
        if (!KushareSwitch.isEnabled(this)) {
            stopSelf()
            return
        }
        registerReceiver(
            bluetoothEvents,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            },
        )
        runCatching {
            getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                networkCallback,
            )
        }
        onRadiosChanged()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SCAN) startScan()
        return START_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        runCatching { unregisterReceiver(bluetoothEvents) }
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        stopLink()
        serviceScope.cancel()
        KushareSwitch.paused.value = false
        KushareSwitch.refreshTile(this)
        super.onDestroy()
    }

    /** Abre o cierra el enlace según haya Bluetooth o Wi-Fi. */
    private fun onRadiosChanged() {
        val available = KushareSwitch.radiosAvailable(this)
        when {
            available && linkScope == null -> startLink()
            !available && linkScope != null -> {
                Log.i(TAG, "sin Bluetooth ni Wi-Fi: enlace en pausa")
                stopLink()
            }
        }
        KushareSwitch.paused.value = !available
        startForeground(paused = !available)
        KushareSwitch.refreshTile(this)
    }

    private fun startLink() {
        val link = KushareLink.ensure(this)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        linkScope = scope
        scope.launch {
            try {
                val port = link.startTcpListener(LINK_PORT.toUShort()).toInt()
                nsd.advertise(link.deviceId(), KushareLink.deviceName(this@KushareService), port)
                publishLan()
            } catch (e: Exception) {
                Log.w(TAG, "no se pudo iniciar el enlace por Wi-Fi", e)
            }
        }
        scope.launch { bluetoothAcceptLoop(scope) }
        scope.launch { transferAcceptLoop(scope) }
        scope.launch { bluetoothReconnectLoop(scope) }
        Log.i(TAG, "enlace activo")
    }

    private fun stopLink() {
        linkScope?.cancel()
        linkScope = null
        runCatching { btServer?.close() }
        btServer = null
        runCatching { transferServer?.close() }
        transferServer = null
        scanJob?.cancel()
        nsd.stopAdvertising()
        nsd.stopDiscovery()
        KushareLink.scanning.value = false
        KushareLink.shutdown()
    }

    private fun startScan() {
        val scope = linkScope ?: return
        if (scanJob?.isActive == true) return
        KushareLink.discovered.value = emptyMap()
        KushareLink.scanning.value = true
        nsd.discover(onFound = KushareLink::onSighting, onLost = KushareLink::onSightingLost)
        scanJob =
            scope.launch {
                delay(SCAN_DURATION_MS)
                nsd.stopDiscovery()
                KushareLink.scanning.value = false
            }
    }

    private fun startForeground(paused: Boolean) {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_DEVICES, true),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            Notification.Builder(this, KushareLink.CHANNEL_SERVICE)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(getString(if (paused) R.string.service_paused else R.string.service_running))
                .setContentText(
                    if (paused) {
                        getString(R.string.service_paused_detail)
                    } else {
                        getString(R.string.service_running_detail)
                    },
                )
                .setContentIntent(open)
                .setOngoing(true)
                .apply {
                    if (!paused) {
                        // Android solo deja leer el portapapeles a la app en primer
                        // plano: el botón abre un instante ClipboardActivity.
                        val clip =
                            PendingIntent.getActivity(
                                this@KushareService,
                                1,
                                Intent(this@KushareService, ClipboardActivity::class.java),
                                PendingIntent.FLAG_IMMUTABLE,
                            )
                        addAction(
                            Notification.Action.Builder(null, getString(R.string.clipboard_send), clip).build(),
                        )
                    }
                }
                .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            getSystemService(NotificationManager::class.java).notify(FOREGROUND_ID, notification)
            startForeground(FOREGROUND_ID, notification)
        }
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun bluetoothHelper(): BluetoothHelper? {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        return BluetoothHelper(adapter)
    }

    /** Conexiones RFCOMM entrantes del PC. `accept()` bloquea sin gastar CPU. */
    private suspend fun bluetoothAcceptLoop(scope: CoroutineScope) {
        while (scope.isActive) {
            val helper = bluetoothHelper()
            if (helper == null || !hasBluetoothPermission() || !helper.isEnabled()) {
                // Esperar a que se encienda el Bluetooth (o reintentar en un rato).
                withTimeoutOrNull(BT_RETRY_MAX_MS) { btWakeAccept.receive() }
                continue
            }
            val server =
                try {
                    helper.listenLink()
                } catch (e: Exception) {
                    Log.w(TAG, "no se pudo escuchar el enlace por Bluetooth", e)
                    delay(BT_RETRY_MIN_MS)
                    continue
                }
            btServer = server
            while (scope.isActive) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                val address = socket.remoteDevice.address
                scope.launch {
                    try {
                        val link = KushareLink.service ?: error("enlace apagado")
                        link.acceptBt(BluetoothSocketIo(socket), address)
                    } catch (e: Exception) {
                        Log.i(TAG, "conexión RFCOMM del enlace rechazada: ${e.message}")
                        runCatching { socket.close() }
                    }
                }
            }
            runCatching { server.close() }
        }
    }

    /**
     * Archivos entrantes por Bluetooth (perfil de transferencias, otro UUID
     * que el del enlace): se reciben siempre que kushare esté encendido, sin
     * tener que abrir la pantalla Recibir.
     */
    private suspend fun transferAcceptLoop(scope: CoroutineScope) {
        val downloadDir =
            getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                ?: java.io.File(filesDir, "Download").apply { mkdirs() }
        while (scope.isActive) {
            val helper = bluetoothHelper()
            if (helper == null || !hasBluetoothPermission() || !helper.isEnabled()) {
                withTimeoutOrNull(BT_RETRY_MAX_MS) { btWakeAccept.receive() }
                continue
            }
            val server =
                try {
                    helper.listen()
                } catch (e: Exception) {
                    Log.w(TAG, "no se pudo escuchar transferencias por Bluetooth", e)
                    delay(BT_RETRY_MIN_MS)
                    continue
                }
            transferServer = server
            while (scope.isActive) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                scope.launch {
                    try {
                        uniffi.kushare_mobile.receiveOverBt(
                            BluetoothSocketIo(socket),
                            downloadDir.absolutePath,
                            FileReceiveCallback(this@KushareService),
                        )
                    } catch (e: Exception) {
                        Log.i(TAG, "transferencia Bluetooth fallida: ${e.message}")
                    } finally {
                        runCatching { socket.close() }
                    }
                }
            }
            runCatching { server.close() }
        }
    }

    /**
     * Reconecta por Bluetooth los peers emparejados que no están conectados.
     * Reacciona a eventos (ACL conectado, Bluetooth encendido); sin eventos,
     * reintenta con un intervalo que crece de 1 a 15 minutos.
     */
    private suspend fun bluetoothReconnectLoop(scope: CoroutineScope) {
        var backoff = BT_RETRY_MIN_MS
        while (scope.isActive) {
            val woken = withTimeoutOrNull(backoff) { btWakeReconnect.receive() } != null
            if (woken) {
                delay(2_000) // dar tiempo a que el otro lado registre su perfil
                backoff = BT_RETRY_MIN_MS
            }
            val attempted = tryBluetoothReconnect()
            backoff = if (attempted) (backoff * 2).coerceAtMost(BT_RETRY_MAX_MS) else BT_RETRY_MAX_MS
        }
    }

    /** Devuelve si había algún peer desconectado al que intentar llegar. */
    private suspend fun tryBluetoothReconnect(): Boolean {
        val helper = bluetoothHelper() ?: return false
        if (!hasBluetoothPermission() || !helper.isEnabled()) return false
        val link = KushareLink.service ?: return false
        val bonded = runCatching { helper.bondedPeers().map { it.address } }.getOrDefault(emptyList())
        var attempted = false
        for (peer in link.peers()) {
            val address = peer.btAddress ?: continue
            // Solo dispositivos ya vinculados: conectar a uno que no lo esté
            // abriría el diálogo de vinculación del sistema.
            if (peer.connected || address !in bonded) continue
            attempted = true
            val io = runCatching { helper.connectLink(address) }.getOrNull() ?: continue
            try {
                link.connectBt(io, address)
                Log.i(TAG, "enlace con ${peer.deviceName} restablecido por Bluetooth")
            } catch (e: Exception) {
                Log.i(TAG, "reconexión Bluetooth con ${peer.deviceName} falló: ${e.message}")
                io.close()
            }
        }
        return attempted
    }
}
