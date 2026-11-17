package org.kushare.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import org.kushare.app.ui.KushareTheme

private const val TAG = "KushareMic"
private const val CHANNEL = "mic"
private const val NOTIFICATION_ID = 7304
private const val REQUEST_NOTIFICATION_ID = 7305

/** Bloques de 40 ms. */
private const val BLOCK_MS = 40

/** Lo que pidió el PC: a quién mandar el audio y en qué formato. */
data class MicRequest(
    val deviceId: String,
    val rate: Int,
    val adpcm: Boolean,
)

/**
 * El teléfono como micrófono del PC. Como la webcam: Android exige que la
 * app esté a la vista para empezar a usar el micrófono, así que se abre
 * [MicActivity] (sola si kushare puede mostrarse sobre otras apps; si no,
 * con una notificación) y esta arranca [MicService].
 */
object Mic {
    val active = MutableStateFlow(false)

    @Volatile var pending: MicRequest? = null

    fun hasPermission(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun requestStart(ctx: Context, req: MicRequest) {
        val app = ctx.applicationContext
        pending = req
        val intent = Intent(app, MicActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Settings.canDrawOverlays(app)) {
            runCatching { app.startActivity(intent) }.onSuccess { return }
        }
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, app.getString(R.string.channel_mic), NotificationManager.IMPORTANCE_HIGH))
        if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val pi = PendingIntent.getActivity(app, 4, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(
            REQUEST_NOTIFICATION_ID,
            Notification
                .Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(app.getString(R.string.mic_request_title))
                .setContentText(app.getString(R.string.mic_request_text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun stop(ctx: Context) {
        pending = null
        ctx.applicationContext.stopService(Intent(ctx.applicationContext, MicService::class.java))
    }
}

/** Servicio en primer plano (tipo micrófono) que graba y manda el audio. */
class MicService : Service() {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var request: MicRequest? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val req = Mic.pending ?: run { stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        request = req
        startForegroundNotification()
        running = true
        thread = Thread({ record(req) }, "kushare-mic").also { it.start() }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_mic), NotificationManager.IMPORTANCE_LOW))
        val open =
            PendingIntent.getActivity(
                this,
                5,
                Intent(this, MicActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val n =
            Notification
                .Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(getString(R.string.mic_active))
                .setContentText(getString(R.string.mic_active_detail))
                .setContentIntent(open)
                .setOngoing(true)
                .build()
        startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    /**
     * Graba con el perfil de llamada (cancelación de eco y ruido) y manda
     * bloques de 40 ms. Si se pidió PCM (Wi-Fi) y se pierde el enlace de
     * datos, pasa solo a ADPCM de 16 kHz para que quepa por Bluetooth.
     */
    @SuppressLint("MissingPermission")
    private fun record(start: MicRequest) {
        var req = start
        var recorder: AudioRecord? = null
        var seq = 0
        var noWifiBlocks = 0
        fun open(r: MicRequest): AudioRecord? {
            val min = AudioRecord.getMinBufferSize(r.rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec =
                runCatching {
                    AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        r.rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(min, r.rate * 2 * BLOCK_MS * 4 / 1000),
                    )
                }.getOrNull()
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                rec?.release()
                return null
            }
            rec.startRecording()
            return rec
        }
        recorder = open(req)
        val svc = KushareLink.service
        if (recorder == null || svc == null) {
            Log.w(TAG, "no se pudo abrir el micrófono")
            svc?.reportMicState(req.deviceId, false, "mic", req.adpcm, req.rate.toUInt())
            running = false
            stopSelf()
            return
        }
        Mic.active.value = true
        svc.reportMicState(req.deviceId, true, "", req.adpcm, req.rate.toUInt())
        var buf = ByteArray(req.rate * 2 * BLOCK_MS / 1000)
        while (running) {
            var read = 0
            while (read < buf.size && running) {
                val n = recorder!!.read(buf, read, buf.size - read)
                if (n <= 0) break
                read += n
            }
            if (!running || read <= 0) break
            val service = KushareLink.service ?: break
            val bulk = service.sendMicAudio(req.deviceId, (++seq).toUInt(), buf.copyOf(read), req.adpcm)
            if (!req.adpcm && !bulk) {
                // Medio segundo sin Wi-Fi: seguir por Bluetooth, comprimido.
                if (++noWifiBlocks >= 500 / BLOCK_MS) {
                    Log.i(TAG, "sin Wi-Fi: el micrófono pasa a ADPCM de 16 kHz")
                    recorder!!.stop()
                    recorder.release()
                    req = req.copy(rate = 16_000, adpcm = true)
                    recorder = open(req) ?: break
                    buf = ByteArray(req.rate * 2 * BLOCK_MS / 1000)
                    service.reportMicState(req.deviceId, true, "", true, req.rate.toUInt())
                }
            } else {
                noWifiBlocks = 0
            }
        }
        runCatching { recorder?.stop() }
        recorder?.release()
        request = req
        if (running) {
            // Terminó solo (error de lectura o sin enlace): avisar y parar.
            running = false
            KushareLink.service?.reportMicState(req.deviceId, false, "mic", req.adpcm, req.rate.toUInt())
            stopSelf()
        }
    }

    override fun onDestroy() {
        running = false
        thread?.join(500)
        Mic.active.value = false
        request?.let { KushareLink.service?.reportMicState(it.deviceId, false, "", it.adpcm, it.rate.toUInt()) }
        super.onDestroy()
    }
}

/**
 * Pantalla que arranca el micrófono (Android exige que la app esté a la
 * vista) y permite pararlo. Se puede salir: sigue en el servicio.
 */
class MicActivity : ComponentActivity() {
    private val permission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startService() else finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        maybeStart()
        setContent {
            KushareTheme {
                val active by Mic.active.collectAsState()
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(if (active) R.string.mic_active else R.string.mic_starting),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(
                            stringResource(R.string.mic_active_detail),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Button(onClick = {
                            Mic.stop(this@MicActivity)
                            finish()
                        }) { Text(stringResource(R.string.mic_stop)) }
                        OutlinedButton(onClick = { moveTaskToBack(true) }) { Text(stringResource(R.string.webcam_hide)) }
                    }
                }
                // Parada desde el PC: cerrar esta pantalla.
                var wasActive by remember { mutableStateOf(false) }
                LaunchedEffect(active) {
                    if (active) wasActive = true else if (wasActive) finish()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        maybeStart()
    }

    private fun maybeStart() {
        getSystemService(NotificationManager::class.java).cancel(REQUEST_NOTIFICATION_ID)
        if (!Mic.active.value && Mic.pending != null) {
            if (Mic.hasPermission(this)) startService() else permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startService() {
        ContextCompat.startForegroundService(this, Intent(this, MicService::class.java))
    }
}
