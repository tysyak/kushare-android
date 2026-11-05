package org.kushare.app

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "KushareService"
private const val FOREGROUND_ID = 1
private const val BT_RECONNECT_INTERVAL_MS = 30_000L

/**
 * Servicio en primer plano dueño del enlace persistente: mantiene vivo el
 * proceso, escucha por TCP y RFCOMM, se anuncia y descubre peers por mDNS,
 * y reintenta por Bluetooth los peers que no están conectados (la
 * reconexión por Wi-Fi la hace el propio Rust).
 */
class KushareService : Service() {
    companion object {
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, KushareService::class.java))
            }.onFailure { Log.w(TAG, "no se pudo arrancar el servicio", it) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var nsd: LinkNsd
    private var btServer: BluetoothServerSocket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val link = KushareLink.ensure(this)
        startForegroundCompat()

        nsd = LinkNsd(this)
        scope.launch {
            try {
                val port = link.startTcpListener(0u).toInt()
                nsd.advertise(link.deviceId(), KushareLink.deviceName(this@KushareService), port)
                nsd.discover(onFound = KushareLink::onSighting, onLost = KushareLink::onSightingLost)
            } catch (e: Exception) {
                Log.w(TAG, "no se pudo iniciar el enlace por Wi-Fi", e)
            }
        }
        scope.launch { bluetoothAcceptLoop() }
        scope.launch { bluetoothReconnectLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        runCatching { btServer?.close() }
        nsd.stopAdvertising()
        nsd.stopDiscovery()
        super.onDestroy()
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

    /** Conexiones RFCOMM entrantes del PC (cuando no hay Wi-Fi común). */
    private suspend fun bluetoothAcceptLoop() {
        while (scope.isActive) {
            val helper = bluetoothHelper()
            if (helper == null || !hasBluetoothPermission() || !helper.isEnabled()) {
                delay(BT_RECONNECT_INTERVAL_MS)
                continue
            }
            val server =
                try {
                    helper.listenLink()
                } catch (e: Exception) {
                    Log.w(TAG, "no se pudo escuchar el enlace por Bluetooth", e)
                    delay(BT_RECONNECT_INTERVAL_MS)
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

    /** Reintenta por Bluetooth los peers emparejados que no están conectados. */
    private suspend fun bluetoothReconnectLoop() {
        while (scope.isActive) {
            delay(BT_RECONNECT_INTERVAL_MS)
            val helper = bluetoothHelper() ?: continue
            if (!hasBluetoothPermission() || !helper.isEnabled()) continue
            val link = KushareLink.service ?: continue
            val bonded = runCatching { helper.bondedPeers().map { it.address } }.getOrDefault(emptyList())
            for (peer in link.peers()) {
                val address = peer.btAddress ?: continue
                // Solo dispositivos ya emparejados por Bluetooth: conectar a
                // uno que no lo esté abriría el diálogo de emparejado.
                if (peer.connected || address !in bonded) continue
                val io = runCatching { helper.connectLink(address) }.getOrNull() ?: continue
                try {
                    link.connectBt(io, address)
                    Log.i(TAG, "enlace con ${peer.deviceName} restablecido por Bluetooth")
                } catch (e: Exception) {
                    Log.i(TAG, "reconexión Bluetooth con ${peer.deviceName} falló: ${e.message}")
                    io.close()
                }
            }
        }
    }
}
