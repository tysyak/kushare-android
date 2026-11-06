package org.kushare.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import uniffi.kushare_mobile.LinkCallback
import uniffi.kushare_mobile.LinkService
import uniffi.kushare_mobile.MobileAudioOutput
import uniffi.kushare_mobile.MobileBattery
import uniffi.kushare_mobile.MobilePlayer
import uniffi.kushare_mobile.MobilePeer
import uniffi.kushare_mobile.MobileRemoteNotification

private const val TAG = "KushareLink"

/** Un dispositivo kushare visto por mDNS (`_kushare-link._tcp`). */
data class LinkSighting(val deviceId: String, val name: String, val host: String, val port: Int)

/** PIN pendiente de confirmar por el usuario. */
data class PairingPrompt(val deviceId: String, val deviceName: String, val pin: String)

/**
 * Estado del enlace persistente compartido por el servicio en primer
 * plano ([KushareService]), el lector de notificaciones
 * ([KushareNotificationListener]) y la UI. El [LinkService] de Rust vive
 * aquí, una sola instancia por proceso.
 */
object KushareLink {
    const val CHANNEL_PC = "pc_notifications"
    const val CHANNEL_PAIRING = "pairing"
    const val CHANNEL_SERVICE = "service"

    @Volatile
    var service: LinkService? = null
        private set

    private lateinit var appContext: Context

    val peers = MutableStateFlow<List<MobilePeer>>(emptyList())
    val discovered = MutableStateFlow<Map<String, LinkSighting>>(emptyMap())

    /** Hay una búsqueda de dispositivos en curso (solo bajo demanda). */
    val scanning = MutableStateFlow(false)
    val pendingPairing = MutableStateFlow<PairingPrompt?>(null)
    val messages = MutableStateFlow<String?>(null)

    /** Prepara contexto y canales de notificación, sin abrir el enlace. */
    @Synchronized
    fun attach(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        createChannels()
    }

    /** Abre el enlace si no está abierto. Solo lo llama [KushareService]. */
    @Synchronized
    fun ensure(context: Context): LinkService {
        service?.let { return it }
        attach(context)
        val svc =
            LinkService(
                File(appContext.filesDir, "link").absolutePath,
                File(appContext.cacheDir, "link").absolutePath,
                deviceName(appContext),
                Callback,
            )
        service = svc
        PhoneFeatures.start(appContext)
        refreshPeers()
        Log.i(TAG, "enlace listo, device_id=${svc.deviceId()}")
        return svc
    }

    fun deviceName(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?: Build.MODEL
            ?: "Android"

    fun refreshPeers() {
        service?.let { svc ->
            peers.value = svc.peers()
            peers.value.filter { !it.connected }.forEach { PhoneFeatures.onPeerDisconnected(it.deviceId) }
        }
        if (::appContext.isInitialized) KushareSwitch.refreshTile(appContext)
    }

    /**
     * Cierra el enlace (kushare apagado o en pausa): desconecta, detiene la
     * escucha y la reconexión, y quita los espejos de notificaciones del PC
     * (ya no se pueden mantener sincronizados). Los peers siguen emparejados.
     */
    @Synchronized
    fun shutdown() {
        val svc = service ?: return
        service = null
        PhoneFeatures.stop()
        runCatching { svc.shutdown() }
        runCatching { svc.close() }
        peers.value = peers.value.map { it.copy(connected = false, transport = null) }
        pendingPairing.value = null
        discovered.value = emptyMap()
        if (::appContext.isInitialized) {
            val nm = notificationManager()
            nm.activeNotifications
                .filter { it.tag?.startsWith("pc/") == true || it.tag?.startsWith("pair/") == true }
                .forEach { nm.cancel(it.tag, it.id) }
            KushareSwitch.refreshTile(appContext)
        }
        Log.i(TAG, "enlace cerrado")
    }

    fun respondPairing(deviceId: String, accept: Boolean) {
        service?.respondPairing(deviceId, accept)
        pendingPairing.update { if (it?.deviceId == deviceId) null else it }
        if (::appContext.isInitialized) notificationManager().cancel(pairingTag(deviceId), PAIRING_ID)
    }

    private fun notificationManager() = appContext.getSystemService(NotificationManager::class.java)

    private fun createChannels() {
        val nm = notificationManager()
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PC, appContext.getString(R.string.channel_pc), NotificationManager.IMPORTANCE_HIGH),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PAIRING, appContext.getString(R.string.channel_pairing), NotificationManager.IMPORTANCE_HIGH),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, appContext.getString(R.string.channel_service), NotificationManager.IMPORTANCE_MIN),
        )
    }

    private const val PAIRING_ID = 1001

    private fun pairingTag(deviceId: String) = "pair/$deviceId"

    private fun mirrorTag(deviceId: String, key: String) = "pc/$deviceId/$key"

    private fun broadcast(action: String, requestCode: Int, extras: Intent.() -> Unit): PendingIntent =
        PendingIntent.getBroadcast(
            appContext,
            requestCode,
            Intent(appContext, LinkActionReceiver::class.java).setAction(action).apply(extras),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun showPairingNotification(prompt: PairingPrompt) {
        val accept =
            broadcast(LinkActionReceiver.ACTION_PAIR, ("a" + prompt.deviceId).hashCode()) {
                putExtra(LinkActionReceiver.EXTRA_DEVICE_ID, prompt.deviceId)
                putExtra(LinkActionReceiver.EXTRA_ACCEPT, true)
            }
        val reject =
            broadcast(LinkActionReceiver.ACTION_PAIR, ("r" + prompt.deviceId).hashCode()) {
                putExtra(LinkActionReceiver.EXTRA_DEVICE_ID, prompt.deviceId)
                putExtra(LinkActionReceiver.EXTRA_ACCEPT, false)
            }
        val open =
            PendingIntent.getActivity(
                appContext,
                0,
                Intent(appContext, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_DEVICES, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            Notification.Builder(appContext, CHANNEL_PAIRING)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(appContext.getString(R.string.devices_pair_with, prompt.deviceName))
                .setContentText(appContext.getString(R.string.pair_notification_text, prompt.pin))
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, appContext.getString(R.string.common_accept), accept).build())
                .addAction(Notification.Action.Builder(null, appContext.getString(R.string.common_reject), reject).build())
                .build()
        runCatching { notificationManager().notify(pairingTag(prompt.deviceId), PAIRING_ID, notification) }
    }

    private fun showMirror(n: MobileRemoteNotification) {
        val tag = mirrorTag(n.deviceId, n.key)
        val dismissed =
            broadcast(LinkActionReceiver.ACTION_MIRROR_DISMISSED, tag.hashCode()) {
                putExtra(LinkActionReceiver.EXTRA_DEVICE_ID, n.deviceId)
                putExtra(LinkActionReceiver.EXTRA_KEY, n.key)
            }
        val builder =
            Notification.Builder(appContext, CHANNEL_PC)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(n.title.ifBlank { n.appName })
                .setContentText(n.text)
                .setStyle(Notification.BigTextStyle().bigText(n.text))
                .setSubText("${n.appName} · ${n.deviceName}")
                .setWhen(n.timestampMs)
                .setShowWhen(true)
                .setDeleteIntent(dismissed)
                .setAutoCancel(true)
        n.iconPath?.let { path -> BitmapFactory.decodeFile(path)?.let { builder.setLargeIcon(it) } }
        runCatching { notificationManager().notify(tag, 0, builder.build()) }
            .onFailure { Log.w(TAG, "no se pudo mostrar la notificación del PC", it) }
    }

    /** El usuario descartó en el teléfono el espejo de una notificación del PC. */
    fun onMirrorDismissed(deviceId: String, key: String) {
        service?.dismissRemoteNotification(deviceId, key)
    }

    private object Callback : LinkCallback {
        override fun onPairingRequested(deviceId: String, deviceName: String, pin: String, initiatedLocally: Boolean) {
            val prompt = PairingPrompt(deviceId, deviceName, pin)
            pendingPairing.value = prompt
            showPairingNotification(prompt)
        }

        override fun onPairingFinished(deviceId: String, deviceName: String, accepted: Boolean) {
            pendingPairing.update { if (it?.deviceId == deviceId) null else it }
            notificationManager().cancel(pairingTag(deviceId), PAIRING_ID)
            messages.value = appContext.getString(if (accepted) R.string.msg_paired else R.string.msg_not_paired, deviceName)
        }

        override fun onPeersChanged() = refreshPeers()

        override fun onNotificationPosted(notification: MobileRemoteNotification) = showMirror(notification)

        override fun onNotificationRemoved(deviceId: String, key: String) {
            notificationManager().cancel(mirrorTag(deviceId, key), 0)
        }

        override fun onDismissLocal(deviceId: String, key: String) {
            KushareNotificationListener.instance?.dismiss(key)
        }

        override fun onActionRequested(deviceId: String, key: String, actionId: String) {
            KushareNotificationListener.instance?.invokeAction(key, actionId)
        }

        override fun onReplyRequested(deviceId: String, key: String, actionId: String, text: String) {
            KushareNotificationListener.instance?.reply(key, actionId, text)
        }

        override fun onPeerBattery(deviceId: String, battery: MobileBattery) =
            PhoneFeatures.onPeerBattery(deviceId, battery)

        override fun onPeerPlayers(deviceId: String, players: List<MobilePlayer>) =
            PhoneFeatures.onPeerPlayers(deviceId, players)

        override fun onPeerAudioOutputs(deviceId: String, selectable: Boolean, outputs: List<MobileAudioOutput>) =
            PhoneFeatures.onPeerOutputs(deviceId, selectable, outputs)

        override fun onMediaCommand(deviceId: String, playerId: String, action: String) =
            PhoneFeatures.mediaCommand(playerId, action)

        override fun onSetAudioOutput(deviceId: String, outputId: String) {
            // Android no permite a una app elegir la salida de las demás.
        }

        override fun onSetRinger(deviceId: String, mode: String) = PhoneFeatures.setRinger(mode)

        override fun onCallCommand(deviceId: String, action: String) = PhoneFeatures.callCommand(action)

        override fun onPhotoListRequest(deviceId: String, requestId: ULong, offset: UInt, limit: UInt) =
            PhoneFeatures.onPhotoListRequest(deviceId, requestId, offset, limit)

        override fun onPhotoRequest(deviceId: String, requestId: ULong, photoId: String) =
            PhoneFeatures.onPhotoRequest(deviceId, requestId, photoId)
    }

    fun onSighting(sighting: LinkSighting) {
        val svc = service ?: return
        if (sighting.deviceId == svc.deviceId()) return
        discovered.update { it + (sighting.deviceId to sighting) }
        svc.noteSighting(sighting.deviceId, sighting.host, sighting.port.toUShort())
    }

    fun onSightingLost(deviceId: String) {
        discovered.update { it - deviceId }
    }
}
