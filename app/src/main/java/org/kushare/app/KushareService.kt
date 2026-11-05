package org.kushare.app

import android.Manifest
import android.app.Notification
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
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.util.Log
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
 * Servicio en primer plano dueño del enlace persistente. Pensado para
 * gastar poca batería con un enlace Bluetooth siempre activo:
 *
 * - **No escanea la red de forma continua**: solo anuncia (pasivo) y busca
 *   cuando el usuario pulsa "Buscar" ([ACTION_SCAN], 30 s).
 * - **Reconexión por eventos**: se reintenta por Bluetooth cuando el
 *   sistema avisa de que un dispositivo se conectó (ACL) o se encendió el
 *   Bluetooth, y por Wi-Fi cuando vuelve la red; entre eventos solo hay un
 *   reintento de respaldo cada vez más espaciado (1 → 15 min).
 * - El enlace en sí no despierta la CPU: el lector bloquea en el socket y
 *   el keepalive por Bluetooth es de minutos (ver `LinkConfig`).
 */
class KushareService : Service() {
    companion object {
        const val ACTION_SCAN = "org.kushare.app.SCAN"

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, KushareService::class.java))
            }.onFailure { Log.w(TAG, "no se pudo arrancar el servicio", it) }
        }

        /** Busca dispositivos kushare en la red durante unos segundos. */
        fun scan(context: Context) {
            runCatching {
                context.startForegroundService(
                    Intent(context, KushareService::class.java).setAction(ACTION_SCAN),
                )
            }.onFailure { Log.w(TAG, "no se pudo iniciar la búsqueda", it) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var nsd: LinkNsd
    private var btServer: BluetoothServerSocket? = null
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
                    BluetoothAdapter.ACTION_STATE_CHANGED ->
                        if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON) {
                            wakeBluetooth()
                        }
                }
            }
        }

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                KushareLink.service?.wakeReconnect()
            }
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val link = KushareLink.ensure(this)
        startForegroundCompat()

        nsd = LinkNsd(this)
        scope.launch {
            try {
                val port = link.startTcpListener(LINK_PORT.toUShort()).toInt()
                nsd.advertise(link.deviceId(), KushareLink.deviceName(this@KushareService), port)
            } catch (e: Exception) {
                Log.w(TAG, "no se pudo iniciar el enlace por Wi-Fi", e)
            }
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

        scope.launch { bluetoothAcceptLoop() }
        scope.launch { bluetoothReconnectLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SCAN) startScan()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { unregisterReceiver(bluetoothEvents) }
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        runCatching { btServer?.close() }
        nsd.stopAdvertising()
        nsd.stopDiscovery()
        KushareLink.scanning.value = false
        super.onDestroy()
    }

    private fun startScan() {
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

    private fun startForegroundCompat() {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_DEVICES, true),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            Notification.Builder(this, KushareLink.CHANNEL_SERVICE)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("kushare conectado en segundo plano")
                .setContentText("Sincronizando notificaciones con tus dispositivos")
                .setContentIntent(open)
                .setOngoing(true)
                .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
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
    private suspend fun bluetoothAcceptLoop() {
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
                        KushareLink.ensure(this@KushareService).acceptBt(BluetoothSocketIo(socket), address)
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
     * Reconecta por Bluetooth los peers emparejados que no están conectados.
     * Reacciona a eventos (ACL conectado, Bluetooth encendido); sin eventos,
     * reintenta con un intervalo que crece de 1 a 15 minutos.
     */
    private suspend fun bluetoothReconnectLoop() {
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
