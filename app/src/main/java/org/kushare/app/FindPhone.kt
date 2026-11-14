package org.kushare.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow

private const val TAG = "KushareFind"
private const val CHANNEL = "find_phone"
private const val NOTIFICATION_ID = 7301

/** Tiempo de sonido: el mismo que «Encontrar mi dispositivo» de Google. */
private const val RING_MS = 5 * 60 * 1000L

/** Parpadeo de la linterna (encendida / apagada). */
private const val BLINK_MS = 500L

/**
 * «Encontrar teléfono», pedido desde el PC: suena a todo volumen por el canal
 * de alarma aunque esté en silencio, vibración o No molestar (si kushare
 * tiene acceso a No molestar, lo quita mientras suena), vibra y hace
 * parpadear la linterna. Se para al pulsar el botón de la pantalla, desde
 * la notificación, desde el PC o a los 5 min, y deja todo como estaba.
 */
object FindPhone {
    val ringing = MutableStateFlow(false)

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var torchId: String? = null
    private var torchOn = false

    /** Lo que había antes, para restaurarlo: (volumen de alarma, timbre, No molestar). */
    private var saved: Triple<Int, Int, Int>? = null

    private val timeout = Runnable { stop(null) }
    private val blink =
        object : Runnable {
            override fun run() {
                val ctx = appContext ?: return
                val id = torchId ?: return
                torchOn = !torchOn
                runCatching { ctx.getSystemService(CameraManager::class.java).setTorchMode(id, torchOn) }
                main.postDelayed(this, BLINK_MS)
            }
        }
    private var appContext: Context? = null

    fun start(ctx: Context) {
        main.post {
            if (ringing.value) return@post
            val app = ctx.applicationContext
            appContext = app
            ringing.value = true
            val audio = app.getSystemService(AudioManager::class.java)
            val nm = app.getSystemService(NotificationManager::class.java)
            saved = Triple(audio.getStreamVolume(AudioManager.STREAM_ALARM), audio.ringerMode, nm.currentInterruptionFilter)
            // Quitar No molestar y el modo vibración/silencio (si se tiene permiso).
            if (nm.isNotificationPolicyAccessGranted) {
                runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) }
                runCatching { audio.ringerMode = AudioManager.RINGER_MODE_NORMAL }
            }
            runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
            playSound(app)
            vibrate(app)
            startTorch(app)
            showNotification(app)
            // Si Android lo deja (pantalla encendida y en kushare, o permiso de
            // pantalla completa), abrir directamente la pantalla de silenciar.
            runCatching {
                app.startActivity(Intent(app, FindPhoneActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            main.postDelayed(timeout, RING_MS)
            KushareLink.service?.reportFindPhone(true)
        }
    }

    /** Para el sonido y deja todo como estaba. `ctx` puede ser null (tiempo agotado). */
    fun stop(ctx: Context?) {
        main.post {
            if (!ringing.value) return@post
            val app = ctx?.applicationContext ?: appContext ?: return@post
            ringing.value = false
            main.removeCallbacks(timeout)
            main.removeCallbacks(blink)
            runCatching { player?.stop() }
            runCatching { player?.release() }
            player = null
            runCatching { app.getSystemService(VibratorManager::class.java).defaultVibrator.cancel() }
            torchId?.let { id -> runCatching { app.getSystemService(CameraManager::class.java).setTorchMode(id, false) } }
            torchOn = false
            val audio = app.getSystemService(AudioManager::class.java)
            val nm = app.getSystemService(NotificationManager::class.java)
            saved?.let { (volume, ringer, filter) ->
                runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0) }
                if (nm.isNotificationPolicyAccessGranted) {
                    runCatching { audio.ringerMode = ringer }
                    runCatching { nm.setInterruptionFilter(filter) }
                }
            }
            saved = null
            nm.cancel(NOTIFICATION_ID)
            KushareLink.service?.reportFindPhone(false)
        }
    }

    private fun playSound(app: Context) {
        val uri =
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player =
            runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    setDataSource(app, uri)
                    isLooping = true
                    prepare()
                    start()
                }
            }.onFailure { Log.w(TAG, "no se pudo reproducir el tono", it) }.getOrNull()
    }

    private fun vibrate(app: Context) {
        runCatching {
            val pattern = longArrayOf(0, 800, 400)
            app.getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(
                VibrationEffect.createWaveform(pattern, 0),
                android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_ALARM),
            )
        }
    }

    /** Linterna parpadeando (no necesita el permiso de cámara). */
    private fun startTorch(app: Context) {
        val cm = app.getSystemService(CameraManager::class.java)
        torchId =
            runCatching {
                cm.cameraIdList.firstOrNull { id ->
                    cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
            }.getOrNull()
        if (torchId != null) main.post(blink)
    }

    private fun showNotification(app: Context) {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, app.getString(R.string.channel_find_phone), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
        if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val screen =
            PendingIntent.getActivity(
                app,
                0,
                Intent(app, FindPhoneActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val silence =
            PendingIntent.getBroadcast(
                app,
                0,
                Intent(app, FindPhoneReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val n =
            Notification
                .Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(app.getString(R.string.find_title))
                .setContentText(app.getString(R.string.find_text))
                .setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true)
                .setFullScreenIntent(screen, true)
                .setContentIntent(screen)
                .addAction(Notification.Action.Builder(null, app.getString(R.string.find_silence), silence).build())
                .build()
        nm.notify(NOTIFICATION_ID, n)
    }
}

/** Botón «Silenciar» de la notificación. No exportado. */
class FindPhoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = FindPhone.stop(context)
}

/**
 * Pantalla de «Encontrar teléfono»: se ve sobre la pantalla de bloqueo y la
 * enciende; casi todo es un botón blanco enorme para silenciarlo.
 */
class FindPhoneActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val ringing by FindPhone.ringing.collectAsState()
            LaunchedEffect(ringing) { if (!ringing) finish() }
            FindPhoneScreen { FindPhone.stop(this) }
        }
    }
}

@Composable
private fun FindPhoneScreen(onSilence: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF9D06D8)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("kushare", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold)
        Text(
            stringResource(R.string.find_text),
            color = Color.White,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onSilence,
            modifier = Modifier.fillMaxWidth().height(260.dp),
            shape = RoundedCornerShape(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF3B0057)),
        ) {
            Text(
                stringResource(R.string.find_silence_big),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }
}
