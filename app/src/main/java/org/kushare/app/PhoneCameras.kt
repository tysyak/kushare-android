package org.kushare.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.LifecycleService
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import org.kushare.app.ui.KushareTheme
import uniffi.kushare_mobile.MobileCamera

private const val TAG = "KushareCamera"
private const val CHANNEL = "webcam"
private const val NOTIFICATION_ID = 7302

/** Cámaras del teléfono (Camera2), descritas para que el PC las distinga. */
object PhoneCameras {
    fun list(ctx: Context): List<MobileCamera> {
        val cm = ctx.getSystemService(CameraManager::class.java)
        return cm.cameraIdList.mapNotNull { id ->
            runCatching {
                val ch = cm.getCameraCharacteristics(id)
                val facing =
                    when (ch.get(CameraCharacteristics.LENS_FACING)) {
                        CameraMetadata.LENS_FACING_FRONT -> "front"
                        CameraMetadata.LENS_FACING_EXTERNAL -> "external"
                        else -> "back"
                    }
                val size = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
                val cfa = ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
                MobileCamera(
                    id = id,
                    facing = facing,
                    megapixels = size?.let { it.width * it.height / 1_000_000f } ?: 0f,
                    focalMm = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f,
                    logical = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps,
                    infrared = Build.VERSION.SDK_INT >= 29 && cfa == CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR,
                    monochrome = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME in caps,
                )
            }.onFailure { Log.w(TAG, "cámara $id ilegible", it) }.getOrNull()
        }
    }

    fun hasPermission(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** Selector de CameraX para la cámara `id` de Camera2 (vacía = trasera por defecto). */
    @OptIn(ExperimentalCamera2Interop::class)
    fun selector(id: String): CameraSelector =
        if (id.isEmpty()) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector
                .Builder()
                .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == id } }
                .build()
        }

    fun isFront(ctx: Context, id: String): Boolean =
        runCatching {
            ctx.getSystemService(CameraManager::class.java).getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraMetadata.LENS_FACING_FRONT
        }.getOrDefault(false)
}

/** Lo que pidió el PC para la webcam. */
data class WebcamRequest(val deviceId: String, val cameraId: String, val width: Int, val height: Int, val fps: Int, val quality: Int)

/**
 * Webcam para el PC. Android solo deja usar la cámara a un servicio en
 * primer plano que se arranca con la app a la vista, así que el PC abre
 * [WebcamActivity] (o, si no se puede, una notificación para tocar) y es
 * ella la que arranca [WebcamService].
 */
object Webcam {
    val active = MutableStateFlow(false)

    @Volatile var pending: WebcamRequest? = null

    /** Fotogramas mandados y aún no confirmados por el PC. */
    val inFlight = AtomicInteger(0)

    fun requestStart(ctx: Context, req: WebcamRequest) {
        val app = ctx.applicationContext
        if (!PhoneCameras.hasPermission(app)) {
            // Sin permiso: la pantalla lo pide (si se puede abrir).
            pending = req
            openActivity(app)
            return
        }
        pending = req
        openActivity(app)
    }

    private fun openActivity(app: Context) {
        val intent = Intent(app, WebcamActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Con «Mostrar sobre otras apps» se abre sola; si no, notificación.
        if (Settings.canDrawOverlays(app)) {
            runCatching { app.startActivity(intent) }.onSuccess { return }
        }
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, app.getString(R.string.channel_webcam), NotificationManager.IMPORTANCE_HIGH),
        )
        if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val pi = PendingIntent.getActivity(app, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(
            NOTIFICATION_ID,
            Notification
                .Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(app.getString(R.string.webcam_request_title))
                .setContentText(app.getString(R.string.webcam_request_text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun stop(ctx: Context) {
        pending = null
        ctx.applicationContext.stopService(Intent(ctx.applicationContext, WebcamService::class.java))
    }

    fun onAck() {
        if (inFlight.decrementAndGet() < 0) inFlight.set(0)
    }
}

/** Servicio en primer plano (tipo cámara) que manda los fotogramas al PC. */
class WebcamService : LifecycleService() {
    private val executor = Executors.newSingleThreadExecutor()
    private var request: WebcamRequest? = null
    private var seq = 0
    private var lastFrame = 0L
    private var noWifiSince = 0L
    private var analysis: ImageAnalysis? = null

    /** Sigue cómo se sostiene el teléfono: de lado → vídeo apaisado. */
    private val orientation by lazy {
        object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(deg: Int) {
                if (deg == ORIENTATION_UNKNOWN) return
                analysis?.targetRotation =
                    when (deg) {
                        in 45..134 -> android.view.Surface.ROTATION_270
                        in 135..224 -> android.view.Surface.ROTATION_180
                        in 225..314 -> android.view.Surface.ROTATION_90
                        else -> android.view.Surface.ROTATION_0
                    }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val req = Webcam.pending ?: run { stopSelf(); return START_NOT_STICKY }
        request = req
        startForegroundNotification()
        bindCamera(req)
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_webcam), NotificationManager.IMPORTANCE_LOW))
        val stop =
            PendingIntent.getActivity(
                this,
                2,
                Intent(this, WebcamActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val n =
            Notification
                .Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(getString(R.string.webcam_active))
                .setContentText(getString(R.string.webcam_active_detail))
                .setContentIntent(stop)
                .setOngoing(true)
                .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun bindCamera(req: WebcamRequest) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            runCatching {
                val provider = future.get()
                val analysis =
                    ImageAnalysis
                        .Builder()
                        .setResolutionSelector(
                            ResolutionSelector
                                .Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(Size(req.width, req.height), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                                ).build(),
                        ).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build()
                val mirror = PhoneCameras.isFront(this, req.cameraId)
                analysis.setAnalyzer(executor) { image -> onFrame(req, image, mirror) }
                this.analysis = analysis
                if (orientation.canDetectOrientation()) orientation.enable()
                provider.unbindAll()
                provider.bindToLifecycle(this, PhoneCameras.selector(req.cameraId), analysis)
                Webcam.active.value = true
                Webcam.inFlight.set(0)
                KushareLink.service?.reportWebcamState(req.deviceId, true, req.cameraId, "")
            }.onFailure {
                Log.w(TAG, "no se pudo abrir la cámara", it)
                KushareLink.service?.reportWebcamState(req.deviceId, false, req.cameraId, it.message ?: "camera")
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onFrame(req: WebcamRequest, image: ImageProxy, mirror: Boolean) {
        image.use {
            val now = System.currentTimeMillis()
            // Ritmo pedido y como mucho 2 fotogramas sin confirmar (si el PC
            // o la Wi-Fi se retrasan, se descartan en lugar de acumular retraso).
            if (now - lastFrame < 1000L / req.fps || Webcam.inFlight.get() >= 2) return
            lastFrame = now
            val jpeg = toJpeg(image, req.quality) ?: return
            val sent =
                KushareLink.service?.sendWebcamFrame(req.deviceId, (++seq).toUInt(), jpeg, image.imageInfo.rotationDegrees.toUInt(), mirror)
                    ?: false
            if (sent) {
                Webcam.inFlight.incrementAndGet()
                noWifiSince = 0
            } else {
                // Sin enlace de datos Wi-Fi: unos segundos de margen y se para.
                if (noWifiSince == 0L) noWifiSince = now
                if (now - noWifiSince > 5000) {
                    KushareLink.service?.reportWebcamState(req.deviceId, false, req.cameraId, "wifi")
                    stopSelf()
                }
            }
        }
    }

    /** YUV_420_888 → NV21 → JPEG. */
    private fun toJpeg(image: ImageProxy, quality: Int): ByteArray? =
        runCatching {
            val w = image.width
            val h = image.height
            val nv21 = ByteArray(w * h * 3 / 2)
            val y = image.planes[0]
            var pos = 0
            for (row in 0 until h) {
                y.buffer.position(row * y.rowStride)
                y.buffer.get(nv21, pos, w)
                pos += w
            }
            val u = image.planes[1]
            val v = image.planes[2]
            val ub = u.buffer
            val vb = v.buffer
            for (row in 0 until h / 2) {
                for (col in 0 until w / 2) {
                    val vi = row * v.rowStride + col * v.pixelStride
                    val ui = row * u.rowStride + col * u.pixelStride
                    nv21[pos++] = vb.get(vi)
                    nv21[pos++] = ub.get(ui)
                }
            }
            val out = ByteArrayOutputStream(w * h / 4)
            YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), quality, out)
            out.toByteArray()
        }.getOrNull()

    override fun onDestroy() {
        orientation.disable()
        Webcam.active.value = false
        request?.let { KushareLink.service?.reportWebcamState(it.deviceId, false, it.cameraId, "") }
        executor.shutdown()
        super.onDestroy()
    }
}

/**
 * Pantalla que arranca la webcam (Android exige que la app esté a la vista
 * para empezar a usar la cámara) y luego permite pararla. Se puede salir
 * de ella: la webcam sigue en el servicio.
 */
class WebcamActivity : ComponentActivity() {
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
                val active by Webcam.active.collectAsState()
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(if (active) R.string.webcam_active else R.string.webcam_starting), style = MaterialTheme.typography.headlineMedium)
                        Text(
                            stringResource(R.string.webcam_active_detail),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Button(onClick = {
                            Webcam.stop(this@WebcamActivity)
                            finish()
                        }) { Text(stringResource(R.string.webcam_stop)) }
                        Button(onClick = { moveTaskToBack(true) }) { Text(stringResource(R.string.webcam_hide)) }
                    }
                }
                // Parada desde el PC (o por falta de Wi-Fi): cerrar esta pantalla.
                var wasActive by remember { mutableStateOf(false) }
                LaunchedEffect(active) {
                    if (active) wasActive = true else if (wasActive) finish()
                }
            }
        }
    }

    // Pantalla reutilizada (`singleTask`): el PC volvió a pedir la webcam.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        maybeStart()
    }

    private fun maybeStart() {
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        if (!Webcam.active.value && Webcam.pending != null) {
            if (PhoneCameras.hasPermission(this)) startService() else permission.launch(Manifest.permission.CAMERA)
        }
    }

    @SuppressLint("NewApi")
    private fun startService() {
        ContextCompat.startForegroundService(this, Intent(this, WebcamService::class.java))
    }
}
