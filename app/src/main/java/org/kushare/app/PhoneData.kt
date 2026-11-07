package org.kushare.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.WallpaperManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uniffi.kushare_mobile.LinkService
import uniffi.kushare_mobile.MobileCallLogEntry
import uniffi.kushare_mobile.MobileContact
import uniffi.kushare_mobile.MobileSms
import uniffi.kushare_mobile.MobileSmsThread

private const val TAG = "KushareData"

/** Código de error que el PC traduce a "falta el permiso en el teléfono". */
private const val NO_PERMISSION = "permission"

/**
 * Datos del teléfono que pide el PC (al estilo de Enlace Móvil): SMS,
 * registro de llamadas, contactos, llamar, notificaciones activas, fondo
 * de pantalla, borrar fotos y el portapapeles. Cada función responde con
 * un error `"permission"` si el usuario no concedió su permiso (se piden
 * en Ajustes → Funciones del teléfono).
 */
object PhoneData {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var smsObserver: ContentObserver? = null
    private val names = HashMap<String, String>()

    private fun svc(): LinkService? = KushareLink.service

    private fun has(ctx: Context, vararg permissions: String) =
        permissions.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun hasSmsPermissions(ctx: Context) = has(ctx, Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS)

    fun hasCallLogPermissions(ctx: Context) =
        has(ctx, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)

    /** El fondo de pantalla solo se puede leer con "acceso a todos los archivos". */
    fun hasWallpaperAccess() = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    @Synchronized
    fun start(ctx: Context) {
        if (context != null) return
        context = ctx.applicationContext
        startSmsObserver(ctx.applicationContext)
    }

    @Synchronized
    fun stop() {
        val app = context ?: return
        smsObserver?.let { app.contentResolver.unregisterContentObserver(it) }
        smsObserver = null
        main.removeCallbacks(smsChanged)
        context = null
    }

    fun onPermissionsChanged() {
        val app = context ?: return
        main.post { if (smsObserver == null) startSmsObserver(app) }
        synchronized(names) { names.clear() }
    }

    // --- SMS ---------------------------------------------------------------

    private val smsChanged = Runnable { svc()?.notifySmsChanged("") }

    private fun startSmsObserver(app: Context) {
        if (!has(app, Manifest.permission.READ_SMS)) return
        val observer =
            object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) {
                    main.removeCallbacks(smsChanged)
                    main.postDelayed(smsChanged, 1000)
                }
            }
        runCatching { app.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer) }
            .onSuccess { smsObserver = observer }
    }

    /** Nombre del contacto de un número (con caché), o "". */
    private fun contactName(ctx: Context, number: String): String {
        if (number.isBlank() || !has(ctx, Manifest.permission.READ_CONTACTS)) return ""
        synchronized(names) { names[number]?.let { return it } }
        val name =
            runCatching {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
                ctx.contentResolver
                    .query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull().orEmpty()
        synchronized(names) { names[number] = name }
        return name
    }

    fun onSmsThreadsRequest(deviceId: String, requestId: ULong, offset: UInt, limit: UInt) {
        val app = context ?: return
        scope.launch {
            val svc = svc() ?: return@launch
            if (!has(app, Manifest.permission.READ_SMS)) {
                runCatching { svc.sendSmsThreads(deviceId, requestId, 0u, emptyList(), NO_PERMISSION) }
                return@launch
            }
            val result = runCatching { queryThreads(app, offset.toInt(), limit.toInt()) }
            result.onFailure { Log.w(TAG, "no se pudieron leer las conversaciones", it) }
            val (total, threads) = result.getOrDefault(0 to emptyList())
            runCatching {
                svc.sendSmsThreads(deviceId, requestId, total.toUInt(), threads, result.exceptionOrNull()?.message.orEmpty())
            }
        }
    }

    private fun queryThreads(app: Context, offset: Int, limit: Int): Pair<Int, List<MobileSmsThread>> {
        val resolver = app.contentResolver
        // Números de cada destinatario ("canonical addresses").
        val addresses = HashMap<String, String>()
        resolver.query(Uri.parse("content://mms-sms/canonical-addresses"), arrayOf("_id", "address"), null, null, null)
            ?.use { c -> while (c.moveToNext()) addresses[c.getString(0)] = c.getString(1).orEmpty() }
        val out = mutableListOf<MobileSmsThread>()
        var total = 0
        resolver
            .query(
                Uri.parse("content://mms-sms/conversations?simple=true"),
                arrayOf("_id", "date", "message_count", "recipient_ids", "snippet", "read"),
                null,
                null,
                "date DESC",
            )?.use { c ->
                total = c.count
                if (!c.moveToPosition(offset)) return@use
                do {
                    val threadId = c.getLong(0).toString()
                    val numbers =
                        c.getString(3).orEmpty().split(' ').filter { it.isNotBlank() }.mapNotNull { addresses[it] }
                    val address = numbers.joinToString(", ")
                    val name = numbers.map { contactName(app, it).ifEmpty { it } }.joinToString(", ")
                    // ¿El último lo mandé yo? (tipo 2 = enviado)
                    val outgoing =
                        resolver
                            .query(
                                Telephony.Sms.CONTENT_URI,
                                arrayOf(Telephony.Sms.TYPE),
                                "${Telephony.Sms.THREAD_ID}=?",
                                arrayOf(threadId),
                                "${Telephony.Sms.DATE} DESC LIMIT 1",
                            )?.use { if (it.moveToFirst()) it.getInt(0) != Telephony.Sms.MESSAGE_TYPE_INBOX else false } ?: false
                    out +=
                        MobileSmsThread(
                            threadId = threadId,
                            address = address,
                            name = if (name == address) "" else name,
                            snippet = c.getString(4).orEmpty(),
                            dateMs = c.getLong(1),
                            unread = c.getInt(5) == 0,
                            count = c.getInt(2).toUInt(),
                            outgoing = outgoing,
                        )
                } while (out.size < limit && c.moveToNext())
            }
        return total to out
    }

    fun onSmsMessagesRequest(deviceId: String, requestId: ULong, threadId: String, limit: UInt, beforeMs: Long) {
        val app = context ?: return
        scope.launch {
            val svc = svc() ?: return@launch
            if (!has(app, Manifest.permission.READ_SMS)) {
                runCatching { svc.sendSmsMessages(deviceId, requestId, threadId, emptyList(), NO_PERMISSION) }
                return@launch
            }
            val result =
                runCatching {
                    val out = mutableListOf<MobileSms>()
                    val (where, args) =
                        if (beforeMs > 0) {
                            "${Telephony.Sms.THREAD_ID}=? AND ${Telephony.Sms.DATE}<?" to arrayOf(threadId, beforeMs.toString())
                        } else {
                            "${Telephony.Sms.THREAD_ID}=?" to arrayOf(threadId)
                        }
                    app.contentResolver
                        .query(
                            Telephony.Sms.CONTENT_URI,
                            arrayOf(
                                Telephony.Sms._ID,
                                Telephony.Sms.ADDRESS,
                                Telephony.Sms.BODY,
                                Telephony.Sms.DATE,
                                Telephony.Sms.TYPE,
                                Telephony.Sms.READ,
                            ),
                            where,
                            args,
                            "${Telephony.Sms.DATE} DESC",
                        )?.use { c ->
                            while (out.size < limit.toInt() && c.moveToNext()) {
                                val type = c.getInt(4)
                                out +=
                                    MobileSms(
                                        id = c.getLong(0).toString(),
                                        address = c.getString(1).orEmpty(),
                                        body = c.getString(2).orEmpty(),
                                        dateMs = c.getLong(3),
                                        outgoing = type != Telephony.Sms.MESSAGE_TYPE_INBOX,
                                        read = c.getInt(5) != 0,
                                        failed = type == Telephony.Sms.MESSAGE_TYPE_FAILED,
                                    )
                            }
                        }
                    out
                }
            result.onFailure { Log.w(TAG, "no se pudieron leer los mensajes", it) }
            runCatching {
                svc.sendSmsMessages(
                    deviceId,
                    requestId,
                    threadId,
                    result.getOrDefault(emptyList()),
                    result.exceptionOrNull()?.message.orEmpty(),
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun onSendSms(deviceId: String, requestId: ULong, address: String, body: String) {
        val app = context ?: return
        scope.launch {
            val svc = svc() ?: return@launch
            if (!has(app, Manifest.permission.SEND_SMS)) {
                svc.sendCommandResult(deviceId, requestId, false, NO_PERMISSION, "")
                return@launch
            }
            runCatching {
                val sms =
                    if (Build.VERSION.SDK_INT >= 31) app.getSystemService(SmsManager::class.java)
                    else @Suppress("DEPRECATION") SmsManager.getDefault()
                val parts = sms.divideMessage(body)
                if (parts.size > 1) {
                    sms.sendMultipartTextMessage(address, null, parts, null, null)
                } else {
                    sms.sendTextMessage(address, null, body, null, null)
                }
            }.onSuccess {
                svc.sendCommandResult(deviceId, requestId, true, "", "done")
            }.onFailure {
                Log.w(TAG, "no se pudo mandar el SMS", it)
                svc.sendCommandResult(deviceId, requestId, false, it.message ?: "error", "")
            }
        }
    }

    // --- Llamadas y contactos ---------------------------------------------

    fun onCallLogRequest(deviceId: String, requestId: ULong, limit: UInt) {
        val app = context ?: return
        scope.launch {
            val svc = svc() ?: return@launch
            if (!has(app, Manifest.permission.READ_CALL_LOG)) {
                runCatching { svc.sendCallLog(deviceId, requestId, emptyList(), NO_PERMISSION) }
                return@launch
            }
            val result =
                runCatching {
                    val out = mutableListOf<MobileCallLogEntry>()
                    app.contentResolver
                        .query(
                            CallLog.Calls.CONTENT_URI,
                            arrayOf(
                                CallLog.Calls.NUMBER,
                                CallLog.Calls.CACHED_NAME,
                                CallLog.Calls.DATE,
                                CallLog.Calls.DURATION,
                                CallLog.Calls.TYPE,
                            ),
                            null,
                            null,
                            "${CallLog.Calls.DATE} DESC",
                        )?.use { c ->
                            while (out.size < limit.toInt() && c.moveToNext()) {
                                val kind =
                                    when (c.getInt(4)) {
                                        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                                        CallLog.Calls.MISSED_TYPE -> "missed"
                                        CallLog.Calls.REJECTED_TYPE -> "rejected"
                                        CallLog.Calls.BLOCKED_TYPE -> "blocked"
                                        CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
                                        else -> "incoming"
                                    }
                                out +=
                                    MobileCallLogEntry(
                                        number = c.getString(0).orEmpty(),
                                        name = c.getString(1).orEmpty(),
                                        dateMs = c.getLong(2),
                                        durationS = c.getLong(3).coerceIn(0, Int.MAX_VALUE.toLong()).toUInt(),
                                        kind = kind,
                                    )
                            }
                        }
                    out
                }
            result.onFailure { Log.w(TAG, "no se pudo leer el registro de llamadas", it) }
            runCatching {
                svc.sendCallLog(deviceId, requestId, result.getOrDefault(emptyList()), result.exceptionOrNull()?.message.orEmpty())
            }
        }
    }

    fun onContactsRequest(deviceId: String, requestId: ULong) {
        val app = context ?: return
        scope.launch {
            val svc = svc() ?: return@launch
            if (!has(app, Manifest.permission.READ_CONTACTS)) {
                runCatching { svc.sendContacts(deviceId, requestId, emptyList(), NO_PERMISSION) }
                return@launch
            }
            val result =
                runCatching {
                    val byId = LinkedHashMap<Long, Triple<String, MutableList<String>, Boolean>>()
                    app.contentResolver
                        .query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            arrayOf(
                                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                                ContactsContract.CommonDataKinds.Phone.NUMBER,
                                ContactsContract.CommonDataKinds.Phone.STARRED,
                            ),
                            null,
                            null,
                            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
                        )?.use { c ->
                            while (c.moveToNext()) {
                                val number = c.getString(2)?.trim().orEmpty()
                                if (number.isEmpty()) continue
                                val entry =
                                    byId.getOrPut(c.getLong(0)) {
                                        Triple(c.getString(1).orEmpty(), mutableListOf(), c.getInt(3) != 0)
                                    }
                                val digits = number.filter { it.isDigit() }
                                if (entry.second.none { it.filter(Char::isDigit) == digits }) entry.second += number
                            }
                        }
                    byId.values.map { (name, numbers, starred) -> MobileContact(name, numbers, starred) }
                }
            result.onFailure { Log.w(TAG, "no se pudieron leer los contactos", it) }
            runCatching {
                svc.sendContacts(deviceId, requestId, result.getOrDefault(emptyList()), result.exceptionOrNull()?.message.orEmpty())
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun onDial(deviceId: String, requestId: ULong, number: String) {
        val app = context ?: return
        val svc = svc() ?: return
        if (!has(app, Manifest.permission.CALL_PHONE)) {
            svc.sendCommandResult(deviceId, requestId, false, NO_PERMISSION, "")
            return
        }
        main.post {
            runCatching {
                app.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), Bundle())
            }.onSuccess {
                svc.sendCommandResult(deviceId, requestId, true, "", "done")
            }.onFailure {
                Log.w(TAG, "no se pudo llamar", it)
                svc.sendCommandResult(deviceId, requestId, false, it.message ?: "error", "")
            }
        }
    }

    // --- Notificaciones activas -----------------------------------------------

    fun onActiveNotificationsRequest(deviceId: String, requestId: ULong, haveIcons: List<String>) {
        scope.launch {
            val svc = svc() ?: return@launch
            val listener = KushareNotificationListener.instance
            if (listener == null) {
                runCatching { svc.sendActiveNotifications(deviceId, requestId, emptyList(), haveIcons, NO_PERMISSION) }
                return@launch
            }
            val list = runCatching { listener.activeForPc() }.getOrDefault(emptyList())
            runCatching { svc.sendActiveNotifications(deviceId, requestId, list, haveIcons, "") }
                .onFailure { Log.w(TAG, "no se pudieron mandar las notificaciones", it) }
        }
    }

    // --- Fondo de pantalla ----------------------------------------------------

    @SuppressLint("MissingPermission")
    fun onWallpaperRequest(deviceId: String, requestId: ULong, maxSize: UInt) {
        val app = context ?: return
        scope.launch {
            val svc = svc() ?: return@launch
            val wm = WallpaperManager.getInstance(app)
            val colors =
                runCatching {
                    wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.let { c ->
                        listOfNotNull(c.primaryColor, c.secondaryColor, c.tertiaryColor).map { it.toArgb().toUInt() }
                    }
                }.getOrNull().orEmpty()
            // Leer la imagen exige acceso a todos los archivos (Android 13+).
            val drawable = runCatching { wm.drawable }.getOrNull()
            val jpeg = drawable?.let { runCatching { toJpeg(it, maxSize.toInt()) }.getOrNull() } ?: ByteArray(0)
            val error = if (jpeg.isEmpty()) NO_PERMISSION else ""
            runCatching { svc.sendWallpaper(deviceId, requestId, jpeg, colors, error) }
                .onFailure { Log.w(TAG, "no se pudo mandar el fondo de pantalla", it) }
        }
    }

    private fun toJpeg(drawable: Drawable, maxSize: Int): ByteArray {
        val (w, h) =
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                drawable.bitmap.width to drawable.bitmap.height
            } else {
                drawable.intrinsicWidth.coerceAtLeast(1) to drawable.intrinsicHeight.coerceAtLeast(1)
            }
        val scale = (maxSize.toFloat() / maxOf(w, h)).coerceAtMost(1f)
        val (tw, th) = (w * scale).toInt().coerceAtLeast(1) to (h * scale).toInt().coerceAtLeast(1)
        val bitmap =
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                Bitmap.createScaledBitmap(drawable.bitmap, tw, th, true)
            } else {
                Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888).also {
                    drawable.setBounds(0, 0, tw, th)
                    drawable.draw(Canvas(it))
                }
            }
        return ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it)
            it.toByteArray()
        }
    }

    // --- Borrar fotos -----------------------------------------------------------

    fun onPhotoDelete(deviceId: String, requestId: ULong, photoId: String) {
        val app = context ?: return
        scope.launch {
            val svc = svc() ?: return@launch
            val id = photoId.toLongOrNull()
            if (id == null) {
                svc.sendCommandResult(deviceId, requestId, false, "id inválido", "")
                return@launch
            }
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            if (Build.VERSION.SDK_INT >= 30) {
                // Siempre a la papelera (recuperable 30 días) y confirmado en el
                // teléfono: ni con "acceso a todos los archivos" puede el PC (o
                // cualquier programa del PC) borrar fotos sin que te enteres.
                runCatching {
                    val request = MediaStore.createTrashRequest(app.contentResolver, listOf(uri), true)
                    askDeleteConfirmation(app, request)
                }.onSuccess {
                    svc.sendCommandResult(deviceId, requestId, true, "", "confirm")
                }.onFailure {
                    svc.sendCommandResult(deviceId, requestId, false, it.message ?: "error", "")
                }
                return@launch
            }
            val direct = runCatching { app.contentResolver.delete(uri, null, null) > 0 }
            if (direct.getOrNull() == true) {
                svc.sendCommandResult(deviceId, requestId, true, "", "done")
            } else {
                val err = direct.exceptionOrNull()
                svc.sendCommandResult(deviceId, requestId, false, if (err is SecurityException) NO_PERMISSION else err?.message ?: "no existe", "")
            }
        }
    }

    private const val DELETE_CHANNEL = "photo-delete"

    private fun askDeleteConfirmation(app: Context, request: PendingIntent) {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(DELETE_CHANNEL, app.getString(R.string.channel_photo_delete), NotificationManager.IMPORTANCE_HIGH),
        )
        val n =
            NotificationCompat.Builder(app, DELETE_CHANNEL)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(app.getString(R.string.photo_delete_title))
                .setContentText(app.getString(R.string.photo_delete_text))
                .setContentIntent(request)
                .setAutoCancel(true)
                .build()
        nm.notify(DELETE_CHANNEL, (System.currentTimeMillis() and 0xffffff).toInt(), n)
    }

    // --- Portapapeles -------------------------------------------------------------

    private const val PREFS = "kushare"
    private const val KEY_CLIPBOARD = "clipboard_sync"

    /** Último texto visto o puesto (para no reenviar ecos). */
    @Volatile
    private var lastClip: String? = null

    fun clipboardSync(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CLIPBOARD, true)

    fun setClipboardSync(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CLIPBOARD, on).apply()

    /** El PC copió algo: ponerlo en el portapapeles del teléfono. */
    fun onClipboard(text: String) {
        val app = context ?: return
        if (!clipboardSync(app)) return
        lastClip = text
        main.post {
            runCatching {
                app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("kushare", text))
            }.onFailure { Log.w(TAG, "no se pudo poner el portapapeles", it) }
        }
    }

    /**
     * Manda al PC lo que haya copiado en el teléfono. Android solo deja
     * leer el portapapeles a la app que está en primer plano, así que se
     * llama al abrir kushare, desde su notificación o desde el botón de
     * ajustes rápidos ([ClipboardActivity]). Devuelve si se mandó algo.
     */
    fun sendClipboard(ctx: Context, force: Boolean = false): Boolean {
        if (!clipboardSync(ctx) && !force) return false
        val clip = runCatching { ctx.getSystemService(ClipboardManager::class.java).primaryClip }.getOrNull() ?: return false
        val description = clip.description
        if (Build.VERSION.SDK_INT >= 33 &&
            description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
        ) {
            return false
        }
        val text = clip.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
        return sendText(text, force)
    }

    /** Manda `text` al portapapeles del PC (también desde "Compartir"). */
    fun sendText(text: String, force: Boolean = true): Boolean {
        if (text.isBlank() || text.length > 256 * 1024) return false
        if (!force && text == lastClip) return false
        lastClip = text
        val svc = svc() ?: return false
        svc.publishClipboard(text)
        return true
    }
}
