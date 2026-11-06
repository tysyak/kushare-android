package org.kushare.app

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import uniffi.kushare_mobile.MobileAction
import uniffi.kushare_mobile.MobileNotification

private const val TAG = "KushareNotif"
private const val ICON_SIZE = 64

/**
 * Apps que ya son puentes de notificaciones con el PC. Lo que publican
 * suelen ser notificaciones del propio PC (o ecos de las nuestras):
 * reenviarlas crea duplicados o un bucle infinito entre los dos puentes
 * (visto en pruebas reales con KDE Connect).
 */
private val NOTIFICATION_BRIDGES =
    setOf(
        "org.kde.kdeconnect_tp", // KDE Connect
        "com.microsoft.appmanager", // Enlace Móvil (Phone Link)
    )

/**
 * Lee las notificaciones del teléfono (requiere que el usuario conceda
 * "Acceso a notificaciones") y las reenvía por el enlace; también ejecuta
 * lo que pide el PC sobre ellas: descartar, pulsar una acción o responder.
 */
class KushareNotificationListener : NotificationListenerService() {
    companion object {
        @Volatile
        var instance: KushareNotificationListener? = null
            private set
    }

    /** Notificaciones reenviadas que siguen activas, por key. */
    private val active = ConcurrentHashMap<String, StatusBarNotification>()
    private val appIcons = LruCache<String, ByteArray>(64)

    override fun onListenerConnected() {
        instance = this
        KushareLink.attach(this)
        KushareService.start(this) // no hace nada si el usuario apagó kushare
        PhoneFeatures.onListenerConnected()
        // Las ya presentes no se reenvían (evita inundar el PC en cada
        // reconexión), pero se recuerdan para poder actuar sobre ellas.
        runCatching { activeNotifications }.getOrNull()?.forEach { sbn ->
            if (shouldForward(sbn)) active[sbn.key] = sbn
        }
        Log.i(TAG, "lector de notificaciones conectado")
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.notification.category == Notification.CATEGORY_CALL) {
            // Nombre o número del llamante para el aviso de llamada del PC.
            val caller = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            PhoneFeatures.onCallNotification(caller)
        }
        if (!shouldForward(sbn)) return
        active[sbn.key] = sbn
        val service = KushareLink.service ?: return
        runCatching { service.postLocalNotification(toMobile(sbn)) }
            .onFailure { Log.w(TAG, "no se pudo reenviar ${sbn.key}", it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (active.remove(sbn.key) != null) {
            KushareLink.service?.removeLocalNotification(sbn.key)
        }
    }

    private fun shouldForward(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (sbn.packageName == packageName || sbn.packageName in NOTIFICATION_BRIDGES) return false
        if (sbn.isOngoing) return false
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)
        return !title.isNullOrBlank() || !text.isNullOrBlank()
    }

    private fun toMobile(sbn: StatusBarNotification): MobileNotification {
        val n = sbn.notification
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text =
            (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString()
                .orEmpty()
        val appInfo = appInfoOf(sbn)
        val appName = appInfo?.let { packageManager.getApplicationLabel(it).toString() } ?: sbn.packageName
        val actions =
            n.actions.orEmpty().mapIndexedNotNull { index, action ->
                val label = action.title?.toString() ?: return@mapIndexedNotNull null
                MobileAction(
                    id = index.toString(),
                    label = label,
                    isReply = action.remoteInputs?.any { it.allowFreeFormInput } == true,
                )
            }
        return MobileNotification(
            key = sbn.key,
            appId = sbn.packageName,
            appName = appName,
            title = title,
            text = text,
            timestampMs = sbn.postTime,
            iconPng = iconFor(sbn, appInfo),
            actions = actions,
        )
    }

    private fun appInfoOf(sbn: StatusBarNotification): ApplicationInfo? {
        // El sistema adjunta la ApplicationInfo de la app a los extras; así
        // no hace falta visibilidad de paquetes para nombre e icono.
        val fromExtras =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                sbn.notification.extras.getParcelable("android.appInfo", ApplicationInfo::class.java)
            } else {
                @Suppress("DEPRECATION")
                sbn.notification.extras.getParcelable("android.appInfo")
            }
        return fromExtras ?: runCatching { packageManager.getApplicationInfo(sbn.packageName, 0) }.getOrNull()
    }

    /** El icono grande de la notificación (p. ej. foto del contacto) o, si no hay, el de la app. */
    private fun iconFor(sbn: StatusBarNotification, appInfo: ApplicationInfo?): ByteArray? {
        sbn.notification.getLargeIcon()?.loadDrawable(this)?.let { drawable -> return toPng(drawable) }
        appIcons.get(sbn.packageName)?.let { return it }
        val drawable = appInfo?.let { runCatching { packageManager.getApplicationIcon(it) }.getOrNull() } ?: return null
        return toPng(drawable)?.also { appIcons.put(sbn.packageName, it) }
    }

    private fun toPng(drawable: Drawable): ByteArray? =
        runCatching {
            val bitmap =
                if (drawable is BitmapDrawable && drawable.bitmap != null) {
                    Bitmap.createScaledBitmap(drawable.bitmap, ICON_SIZE, ICON_SIZE, true)
                } else {
                    Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888).also {
                        val canvas = Canvas(it)
                        drawable.setBounds(0, 0, ICON_SIZE, ICON_SIZE)
                        drawable.draw(canvas)
                    }
                }
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        }.getOrNull()

    // --- Lo que pide el PC ---

    fun dismiss(key: String) {
        runCatching { cancelNotification(key) }.onFailure { Log.w(TAG, "no se pudo descartar $key", it) }
    }

    fun invokeAction(key: String, actionId: String) {
        val action = actionFor(key, actionId) ?: return
        sendPendingIntent(action.actionIntent, null)
    }

    fun reply(key: String, actionId: String, text: String) {
        val action = actionFor(key, actionId) ?: return
        val inputs = action.remoteInputs ?: return
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
        val fillIn = Intent().also { RemoteInput.addResultsToIntent(inputs, it, results) }
        sendPendingIntent(action.actionIntent, fillIn)
    }

    private fun actionFor(key: String, actionId: String): Notification.Action? {
        val sbn = active[key] ?: run {
            Log.w(TAG, "acción sobre una notificación que ya no existe: $key")
            return null
        }
        return sbn.notification.actions?.getOrNull(actionId.toIntOrNull() ?: return null)
    }

    private fun sendPendingIntent(pendingIntent: PendingIntent, fillIn: Intent?) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options =
                    ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(
                            @Suppress("DEPRECATION") ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                        )
                        .toBundle()
                pendingIntent.send(this, 0, fillIn, null, null, null, options)
            } else {
                pendingIntent.send(this, 0, fillIn)
            }
        }.onFailure { Log.w(TAG, "no se pudo ejecutar la acción", it) }
    }
}
