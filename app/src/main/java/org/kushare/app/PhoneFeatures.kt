package org.kushare.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaRouter2
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.telecom.TelecomManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.util.Size
import androidx.core.content.ContextCompat
import java.io.InputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.kushare_mobile.LinkService
import uniffi.kushare_mobile.MobileAudioOutput
import uniffi.kushare_mobile.MobileBattery
import uniffi.kushare_mobile.MobilePhoto
import uniffi.kushare_mobile.MobilePlayer

private const val TAG = "KushareFeatures"
private const val THUMB_SIZE = 240
private const val PHOTO_CHUNK = 256 * 1024

/** Lo último que mandó un peer (el PC) de sus funciones extra. */
data class PeerExtras(
    val battery: MobileBattery? = null,
    val players: List<MobilePlayer> = emptyList(),
    val outputsSelectable: Boolean = false,
    val outputs: List<MobileAudioOutput> = emptyList(),
)

/**
 * Funciones extra del enlace en el teléfono, al estilo KDE Connect:
 *
 * - publica la batería, el modo de timbre, los reproductores (sesiones
 *   multimedia), las salidas de audio y el estado de las llamadas;
 * - obedece al PC: controlar reproductores, cambiar el timbre, contestar o
 *   colgar, y servir la galería (miniaturas y fotos originales);
 * - guarda lo que publica el PC (batería, reproductores, salidas) para la UI.
 *
 * Todo depende de que el enlace esté abierto: [start] al abrirlo, [stop]
 * al cerrarlo. Cada función degrada sola si falta su permiso.
 */
object PhoneFeatures {
    val peerExtras = MutableStateFlow<Map<String, PeerExtras>>(emptyMap())

    private var context: Context? = null
    private var scope: CoroutineScope? = null
    private val main = Handler(Looper.getMainLooper())
    private val receivers = mutableListOf<BroadcastReceiver>()
    private var audioCallback: AudioDeviceCallback? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var telephonyCallback: TelephonyCallback? = null
    private var photosObserver: ContentObserver? = null
    private val controllers = mutableMapOf<String, Pair<MediaController, MediaController.Callback>>()
    private var lastCallPhase = "idle"

    /** Nombre del llamante sacado de la notificación de llamada del marcador. */
    @Volatile
    var callerFromNotification: String = ""

    private fun svc(): LinkService? = KushareLink.service

    @Synchronized
    fun start(ctx: Context) {
        if (context != null) return
        val app = ctx.applicationContext
        context = app
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        startBattery(app)
        startRinger(app)
        startAudioOutputs(app)
        startMediaSessions(app)
        startCalls(app)
        startPhotosObserver(app)
        PhoneData.start(app)
    }

    @Synchronized
    fun stop() {
        val app = context ?: return
        receivers.forEach { runCatching { app.unregisterReceiver(it) } }
        receivers.clear()
        audioCallback?.let { app.getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(it) }
        audioCallback = null
        photosObserver?.let { app.contentResolver.unregisterContentObserver(it) }
        photosObserver = null
        main.removeCallbacks(photosChanged)
        sessionsListener?.let {
            runCatching { app.getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(it) }
        }
        sessionsListener = null
        controllers.values.forEach { (c, cb) -> c.unregisterCallback(cb) }
        controllers.clear()
        if (Build.VERSION.SDK_INT >= 31) {
            telephonyCallback?.let { app.getSystemService(TelephonyManager::class.java).unregisterTelephonyCallback(it) }
        }
        telephonyCallback = null
        PhoneData.stop()
        scope?.cancel()
        scope = null
        context = null
        peerExtras.value = emptyMap()
    }

    /** El usuario concedió permisos nuevos (llamadas, fotos): empezar lo que faltaba. */
    fun onPermissionsChanged() {
        val app = context ?: return
        PhoneData.onPermissionsChanged()
        main.post {
            if (telephonyCallback == null) startCalls(app)
            if (photosObserver == null) startPhotosObserver(app)
        }
    }

    // --- Galería: avisar al PC de fotos nuevas -----------------------------

    private val photosChanged = Runnable { svc()?.notifyPhotosChanged() }

    /**
     * Vigila MediaStore: una foto o captura nueva dispara varios cambios
     * seguidos (inserción, fin de IS_PENDING…), así que se agrupan y se
     * avisa al PC una sola vez, ~1,5 s después del último.
     */
    private fun startPhotosObserver(app: Context) {
        if (!hasPhotoPermission(app)) return
        val observer =
            object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) {
                    main.removeCallbacks(photosChanged)
                    main.postDelayed(photosChanged, 1500)
                }
            }
        app.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        photosObserver = observer
    }

    /** El listener de notificaciones se conectó: ya se pueden leer las sesiones. */
    fun onListenerConnected() {
        val app = context ?: return
        main.post { if (sessionsListener == null) startMediaSessions(app) }
    }

    private fun register(app: Context, filter: IntentFilter, handler: (Intent) -> Unit) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) = handler(intent)
            }
        // NOT_EXPORTED: solo difusiones del sistema (batería, timbre).
        val sticky = ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receivers += receiver
        sticky?.let(handler)
    }

    // --- Batería -----------------------------------------------------------

    private fun startBattery(app: Context) {
        register(app, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) { intent ->
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            if (level < 0) return@register
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging =
                status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            svc()?.publishBattery(MobileBattery(true, (level * 100 / scale).toUInt(), charging))
        }
    }

    // --- Timbre ------------------------------------------------------------

    private fun ringerName(mode: Int) =
        when (mode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "normal"
        }

    private fun startRinger(app: Context) {
        val audio = app.getSystemService(AudioManager::class.java)
        register(app, IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION)) {
            svc()?.publishRinger(ringerName(audio.ringerMode))
        }
        svc()?.publishRinger(ringerName(audio.ringerMode))
    }

    /** ¿Puede kushare cambiar el modo silencio? (acceso a "No molestar"). */
    fun canChangeRinger(ctx: Context) =
        ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    fun setRinger(mode: String) {
        val app = context ?: return
        val audio = app.getSystemService(AudioManager::class.java)
        val target =
            when (mode) {
                "silent" -> AudioManager.RINGER_MODE_SILENT
                "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                else -> AudioManager.RINGER_MODE_NORMAL
            }
        runCatching { audio.ringerMode = target }
            .onFailure {
                // Sin acceso a "No molestar", Android no deja pasar a/desde silencio.
                Log.w(TAG, "no se pudo cambiar el timbre", it)
                KushareLink.messages.value = app.getString(R.string.msg_ringer_needs_dnd)
            }
        svc()?.publishRinger(ringerName(audio.ringerMode))
    }

    // --- Salidas de audio --------------------------------------------------

    private fun outputName(d: AudioDeviceInfo): String? {
        val app = context ?: return null
        val label = d.productName?.toString()?.takeIf { it.isNotBlank() && it != Build.MODEL }
        return when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> app.getString(R.string.output_speaker)
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> app.getString(R.string.output_wired)
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> label ?: app.getString(R.string.output_usb)
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER ->
                label ?: "Bluetooth"
            AudioDeviceInfo.TYPE_HEARING_AID -> label ?: app.getString(R.string.output_hearing_aid)
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            else -> null
        }
    }

    private fun publishOutputs(app: Context) {
        val audio = app.getSystemService(AudioManager::class.java)
        val devices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { outputName(it) != null }
        val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        val active: Set<Int> =
            if (Build.VERSION.SDK_INT >= 33) {
                runCatching { audio.getAudioDevicesForAttributes(media).map { it.id }.toSet() }.getOrDefault(emptySet())
            } else {
                emptySet()
            }
        // Si Android no lo dice, el sistema prefiere lo último conectado: BT > cable > USB > altavoz.
        val fallback =
            devices.maxByOrNull {
                when (it.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET -> 4
                    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 3
                    AudioDeviceInfo.TYPE_USB_HEADSET -> 2
                    else -> 1
                }
            }
        val outputs =
            devices
                .distinctBy { outputName(it) }
                .map {
                    val isActive = if (active.isNotEmpty()) it.id in active else it.id == fallback?.id
                    MobileAudioOutput(it.id.toString(), outputName(it)!!, isActive)
                }
        // Android no deja a otra app mover el audio de las demás: solo informativo.
        svc()?.publishAudioOutputs(false, outputs)
    }

    private fun startAudioOutputs(app: Context) {
        val cb =
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = publishOutputs(app)

                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = publishOutputs(app)
            }
        app.getSystemService(AudioManager::class.java).registerAudioDeviceCallback(cb, main)
        audioCallback = cb
        publishOutputs(app)
    }

    /** Abre el selector de salida de audio del sistema (Android 14+) o su panel. */
    fun openOutputSwitcher(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 34 && MediaRouter2.getInstance(ctx).showSystemOutputSwitcher()) return
        runCatching {
            ctx.startActivity(
                Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
                    .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", ctx.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { KushareLink.messages.value = ctx.getString(R.string.msg_no_output_switcher) }
    }

    // --- Multimedia (sesiones de otras apps) -------------------------------

    private fun playerId(c: MediaController) = "${c.packageName}#${c.sessionToken.hashCode()}"

    private fun appLabel(app: Context, pkg: String): String =
        runCatching {
            val pm = app.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)

    private fun publishPlayers() {
        val app = context ?: return
        val players =
            controllers.values.map { (c, _) ->
                val md = c.metadata
                val ps = c.playbackState
                val actions = ps?.actions ?: 0L
                val state =
                    when (ps?.state) {
                        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING -> "playing"
                        PlaybackState.STATE_PAUSED -> "paused"
                        else -> "stopped"
                    }
                MobilePlayer(
                    id = playerId(c),
                    name = appLabel(app, c.packageName),
                    title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                    artist =
                        (md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty(),
                    album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
                    state = state,
                    canPlay = actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE) != 0L,
                    canPause = actions and (PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE) != 0L,
                    canNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
                    canPrevious = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
                )
            }
        svc()?.publishPlayers(players)
    }

    private val republish = Runnable { publishPlayers() }

    private fun schedulePublish() {
        main.removeCallbacks(republish)
        main.postDelayed(republish, 300)
    }

    private fun onSessions(list: List<MediaController>?) {
        val current = (list ?: emptyList()).associateBy { playerId(it) }
        (controllers.keys - current.keys).forEach { id ->
            controllers.remove(id)?.let { (c, cb) -> c.unregisterCallback(cb) }
        }
        for ((id, c) in current) {
            if (id in controllers) continue
            val cb =
                object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) = schedulePublish()

                    override fun onMetadataChanged(metadata: MediaMetadata?) = schedulePublish()

                    override fun onSessionDestroyed() = schedulePublish()
                }
            c.registerCallback(cb, main)
            controllers[id] = c to cb
        }
        schedulePublish()
    }

    private fun startMediaSessions(app: Context) {
        val msm = app.getSystemService(MediaSessionManager::class.java)
        val component = ComponentName(app, KushareNotificationListener::class.java)
        val listener = MediaSessionManager.OnActiveSessionsChangedListener { onSessions(it) }
        runCatching {
            msm.addOnActiveSessionsChangedListener(listener, component, main)
            sessionsListener = listener
            onSessions(msm.getActiveSessions(component))
        }.onFailure { Log.i(TAG, "sin acceso a notificaciones: no se leen los reproductores") }
    }

    fun mediaCommand(playerId: String, action: String) {
        main.post {
            val targets =
                if (playerId.isEmpty()) controllers.values.map { it.first }
                else listOfNotNull(controllers[playerId]?.first)
            for (c in targets) {
                val t = c.transportControls
                val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
                when (action) {
                    "play" -> t.play()
                    "pause" -> if (playing || playerId.isNotEmpty()) t.pause()
                    "next" -> t.skipToNext()
                    "previous" -> t.skipToPrevious()
                    "stop" -> t.stop()
                    else -> if (playing) t.pause() else t.play()
                }
            }
        }
    }

    // --- Llamadas ----------------------------------------------------------

    fun hasCallPermissions(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    private fun startCalls(app: Context) {
        if (!hasCallPermissions(app) || Build.VERSION.SDK_INT < 31) return
        val tm = app.getSystemService(TelephonyManager::class.java)
        val cb =
            object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    val phase =
                        when (state) {
                            TelephonyManager.CALL_STATE_RINGING -> "ringing"
                            TelephonyManager.CALL_STATE_OFFHOOK ->
                                if (lastCallPhase == "ringing") "active" else if (lastCallPhase == "idle") "dialing" else "active"
                            else -> "idle"
                        }
                    lastCallPhase = phase
                    publishCall(app)
                }
            }
        runCatching { tm.registerTelephonyCallback(app.mainExecutor, cb) }
            .onSuccess { telephonyCallback = cb }
            .onFailure { Log.w(TAG, "no se pudo vigilar las llamadas", it) }
    }

    /** La notificación del marcador cambió (nombre/número del llamante). */
    fun onCallNotification(caller: String) {
        if (caller == callerFromNotification) return
        callerFromNotification = caller
        context?.let { publishCall(it) }
    }

    private fun publishCall(app: Context) {
        if (lastCallPhase == "idle") callerFromNotification = ""
        val muted = app.getSystemService(AudioManager::class.java).isMicrophoneMute
        val caller = callerFromNotification
        val looksLikeNumber = caller.isNotEmpty() && caller.all { it.isDigit() || it in "+-() " }
        svc()?.publishCallState(
            lastCallPhase,
            if (looksLikeNumber) caller else "",
            if (looksLikeNumber) "" else caller,
            muted,
        )
    }

    @SuppressLint("MissingPermission")
    fun callCommand(action: String) {
        val app = context ?: return
        val telecom = app.getSystemService(TelecomManager::class.java)
        val allowed =
            app.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
        runCatching {
            when (action) {
                "answer" -> if (allowed) telecom.acceptRingingCall()
                "reject", "hangup" -> if (allowed) telecom.endCall()
                "mute", "unmute" -> {
                    app.getSystemService(AudioManager::class.java).isMicrophoneMute = action == "mute"
                    publishCall(app)
                }
            }
            if (!allowed && action in setOf("answer", "reject", "hangup")) {
                KushareLink.messages.value = app.getString(R.string.msg_calls_permission)
            }
        }.onFailure { Log.w(TAG, "orden de llamada fallida: $action", it) }
    }

    // --- Fotos ---------------------------------------------------------------

    val photoPermission =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPhotoPermission(ctx: Context) = ctx.checkSelfPermission(photoPermission) == PackageManager.PERMISSION_GRANTED

    fun onPhotoListRequest(deviceId: String, requestId: ULong, offset: UInt, limit: UInt, haveThumbnails: Set<String>) {
        val app = context ?: return
        val s = scope ?: return
        s.launch(Dispatchers.IO) {
            val (total, photos) =
                if (hasPhotoPermission(app)) {
                    runCatching { queryPhotos(app, offset.toInt(), limit.toInt(), haveThumbnails) }
                        .onFailure { Log.w(TAG, "no se pudo leer la galería", it) }
                        .getOrDefault(0 to emptyList())
                } else {
                    KushareLink.messages.value = app.getString(R.string.msg_photos_permission)
                    0 to emptyList()
                }
            runCatching { svc()?.sendPhotoList(deviceId, requestId, total.toUInt(), photos) }
                .onFailure { Log.w(TAG, "no se pudo mandar la lista de fotos", it) }
        }
    }

    private val photoColumns =
        arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
        )

    private fun queryPhotos(app: Context, offset: Int, limit: Int, haveThumbnails: Set<String>): Pair<Int, List<MobilePhoto>> {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val resolver = app.contentResolver
        val photos = mutableListOf<MobilePhoto>()
        var total = 0
        // Más reciente primero. Se salta con moveToPosition (QUERY_ARG_OFFSET es de API 30).
        resolver.query(uri, photoColumns, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            total = c.count
            if (!c.moveToPosition(offset)) return@use
            do {
                val id = c.getLong(0)
                val item = ContentUris.withAppendedId(uri, id)
                // El PC ya tiene la miniatura en caché: no volver a mandarla.
                val thumb =
                    if (id.toString() in haveThumbnails) ByteArray(0) else runCatching {
                        val bmp = resolver.loadThumbnail(item, Size(THUMB_SIZE, THUMB_SIZE), null)
                        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray()
                    }.getOrDefault(ByteArray(0))
                val taken = c.getLong(2).takeIf { it > 0 } ?: (c.getLong(3) * 1000)
                photos +=
                    MobilePhoto(
                        id = id.toString(),
                        name = c.getString(1).orEmpty(),
                        dateMs = taken,
                        width = c.getInt(4).toUInt(),
                        height = c.getInt(5).toUInt(),
                        size = c.getLong(6).toULong(),
                        thumbnail = thumb,
                    )
            } while (photos.size < limit && c.moveToNext())
        }
        return total to photos
    }

    /** Lee hasta llenar `buf` o hasta el final; devuelve los bytes leídos. */
    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    fun onPhotoRequest(deviceId: String, requestId: ULong, photoId: String, chunkSize: Int, background: Boolean) {
        val app = context ?: return
        val s = scope ?: return
        s.launch(Dispatchers.IO) {
            val svc = svc() ?: return@launch
            val id = photoId.toLongOrNull()
            if (id == null || !hasPhotoPermission(app)) {
                runCatching { svc.sendPhotoData(deviceId, requestId, photoId, "", 0u, ByteArray(0), true, "sin permiso", background) }
                return@launch
            }
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            val name =
                app.contentResolver
                    .query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
                    .orEmpty()
            val result =
                runCatching {
                    app.contentResolver.openInputStream(uri)!!.use { input ->
                        val buf = ByteArray(chunkSize.coerceIn(16 * 1024, PHOTO_CHUNK))
                        var offset = 0L
                        var pending: ByteArray? = null
                        while (true) {
                            val n = readFully(input, buf)
                            val chunk = if (n > 0) buf.copyOf(n) else null
                            // Se manda con un trozo de retraso para marcar `last` en el último.
                            pending?.let {
                                svc.sendPhotoData(deviceId, requestId, photoId, name, offset.toULong(), it, chunk == null, "", background)
                                offset += it.size
                            }
                            if (chunk == null) break
                            pending = chunk
                        }
                        if (offset == 0L) {
                            svc.sendPhotoData(deviceId, requestId, photoId, name, 0u, ByteArray(0), true, "", background)
                        }
                    }
                }
            result.onFailure {
                Log.w(TAG, "no se pudo mandar la foto $photoId", it)
                runCatching {
                    svc.sendPhotoData(deviceId, requestId, photoId, name, 0u, ByteArray(0), true, it.message ?: "error", background)
                }
            }
        }
    }

    // --- Lo que publica el peer ---------------------------------------------

    fun onPeerBattery(deviceId: String, battery: MobileBattery) =
        peerExtras.update { it + (deviceId to (it[deviceId] ?: PeerExtras()).copy(battery = battery)) }

    fun onPeerPlayers(deviceId: String, players: List<MobilePlayer>) =
        peerExtras.update { it + (deviceId to (it[deviceId] ?: PeerExtras()).copy(players = players)) }

    fun onPeerOutputs(deviceId: String, selectable: Boolean, outputs: List<MobileAudioOutput>) =
        peerExtras.update {
            it + (deviceId to (it[deviceId] ?: PeerExtras()).copy(outputsSelectable = selectable, outputs = outputs))
        }

    /** El peer se desconectó: sus reproductores y salidas ya no se controlan. */
    fun onPeerDisconnected(deviceId: String) =
        peerExtras.update { m ->
            m[deviceId]?.let { m + (deviceId to it.copy(players = emptyList(), outputs = emptyList())) } ?: m
        }
}
