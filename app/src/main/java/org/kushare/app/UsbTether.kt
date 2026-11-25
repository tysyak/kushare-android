package org.kushare.app

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

private const val TAG = "KushareUsbTether"
private const val CHANNEL = "usb_tether"
private const val NOTIFICATION_ID = 7305

/**
 * «Compartir internet» del PC por el cable USB (opción avanzada).
 *
 * El PC hizo `adb reverse tcp:<port> tcp:<port>` y mandó `port` y un token
 * por el enlace. Aquí se abre una VPN que captura todo el tráfico del
 * teléfono (salvo el de la propia app, para no meter el enlace dentro) y lo
 * manda por un socket a `127.0.0.1:<port>`: el PC lo saca a internet con su
 * propio NAT. Formato, en ambos sentidos: `[u16 big-endian][paquete IP]`.
 */
object UsbTether {
    internal const val EXTRA_PORT = "port"
    internal const val EXTRA_TOKEN = "token"
    internal const val ACTION_STOP = "org.kushare.app.USB_TETHER_STOP"

    /** El PC pidió compartir internet por USB. */
    fun start(ctx: Context, port: Int, token: ByteArray) {
        val app = ctx.applicationContext
        val intent =
            Intent(app, UsbTetherActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_TOKEN, token)
        // Ya hay permiso de VPN: arrancar directamente, sin pantalla.
        if (VpnService.prepare(app) == null) {
            startService(app, port, token)
            return
        }
        // Hace falta el permiso: la pantalla del sistema necesita una Activity.
        if (Settings.canDrawOverlays(app)) {
            runCatching { app.startActivity(intent) }.onSuccess { return }
        }
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, app.getString(R.string.channel_usb_tether), NotificationManager.IMPORTANCE_HIGH))
        if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            KushareLink.service?.reportUsbTether(false, app.getString(R.string.usb_tether_need_permission))
            return
        }
        val pi = PendingIntent.getActivity(app, 5, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(
            NOTIFICATION_ID,
            Notification
                .Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(app.getString(R.string.usb_tether_title))
                .setContentText(app.getString(R.string.usb_tether_text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build(),
        )
    }

    /** El PC dejó de compartir internet: cerrar la VPN. */
    fun stop(ctx: Context) {
        val app = ctx.applicationContext
        runCatching { app.startService(Intent(app, UsbTetherService::class.java).setAction(ACTION_STOP)) }
    }

    internal fun startService(ctx: Context, port: Int, token: ByteArray) {
        ctx.startService(
            Intent(ctx, UsbTetherService::class.java)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_TOKEN, token),
        )
    }
}

/** Pide el permiso de VPN (solo la primera vez) y arranca el servicio. */
class UsbTetherActivity : Activity() {
    private var port = 0
    private var token = ByteArray(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        port = intent.getIntExtra(UsbTether.EXTRA_PORT, 0)
        token = intent.getByteArrayExtra(UsbTether.EXTRA_TOKEN) ?: ByteArray(0)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        val consent = VpnService.prepare(this)
        if (consent == null) {
            onActivityResult(REQUEST, RESULT_OK, null)
        } else {
            @Suppress("DEPRECATION")
            startActivityForResult(consent, REQUEST)
        }
    }

    @Deprecated("API de Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && port > 0) {
            UsbTether.startService(this, port, token)
        } else {
            KushareLink.service?.reportUsbTether(false, getString(R.string.usb_tether_denied))
        }
        finish()
    }

    private companion object {
        const val REQUEST = 1
    }
}

/** La VPN: paquetes del teléfono ⇄ socket hacia el PC por `adb reverse`. */
class UsbTetherService : VpnService() {
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var socket: Socket? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == UsbTether.ACTION_STOP) {
            shutdown(null)
            return START_NOT_STICKY
        }
        val port = intent?.getIntExtra(UsbTether.EXTRA_PORT, 0) ?: 0
        val token = intent?.getByteArrayExtra(UsbTether.EXTRA_TOKEN) ?: ByteArray(0)
        if (port <= 0 || token.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        close()
        thread(name = "usb-tether-connect") { connect(port, token) }
        return START_NOT_STICKY
    }

    private fun connect(port: Int, token: ByteArray) {
        try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            s.getOutputStream().write(token)
            socket = s
            val pfd =
                Builder()
                    .setSession(getString(R.string.usb_tether_session))
                    .addAddress("10.111.0.2", 30)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("10.111.0.1")
                    .setMtu(1500)
                    .addDisallowedApplication(packageName)
                    .setBlocking(true)
                    .establish() ?: throw IllegalStateException(getString(R.string.usb_tether_denied))
            tun = pfd
            KushareLink.service?.reportUsbTether(true, "")
            val input = DataInputStream(s.getInputStream().buffered(1 shl 16))
            val output = DataOutputStream(s.getOutputStream().buffered(1 shl 16))
            // Teléfono → PC.
            thread(name = "usb-tether-up") {
                val tunIn = FileInputStream(pfd.fileDescriptor)
                val buf = ByteArray(65535)
                runCatching {
                    while (true) {
                        val n = tunIn.read(buf)
                        if (n <= 0) continue
                        output.writeShort(n)
                        output.write(buf, 0, n)
                        // Un paquete por envío: `available()` no sirve en el
                        // descriptor de la VPN para juntarlos (se quedaban
                        // paquetes sin mandar).
                        output.flush()
                    }
                }.onFailure { shutdown(it.message) }
            }
            // PC → teléfono.
            val tunOut = FileOutputStream(pfd.fileDescriptor)
            val buf = ByteArray(65535)
            runCatching {
                while (true) {
                    val n = input.readUnsignedShort()
                    input.readFully(buf, 0, n)
                    tunOut.write(buf, 0, n)
                }
            }.onFailure { shutdown(it.message) }
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo conectar con el PC", e)
            shutdown(e.message ?: e.toString())
        }
    }

    @Synchronized
    private fun shutdown(error: String?) {
        if (tun == null && socket == null) {
            stopSelf()
            return
        }
        close()
        KushareLink.service?.reportUsbTether(false, error.orEmpty())
        stopSelf()
    }

    private fun close() {
        runCatching { socket?.close() }
        runCatching { tun?.close() }
        socket = null
        tun = null
    }

    override fun onRevoke() {
        // El usuario cerró la VPN desde el sistema.
        shutdown(null)
        super.onRevoke()
    }

    override fun onDestroy() {
        close()
        super.onDestroy()
    }
}
