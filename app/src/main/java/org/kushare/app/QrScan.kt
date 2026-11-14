package org.kushare.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import org.kushare.app.ui.KushareTheme

private const val CHANNEL = "qr"
private const val NOTIFICATION_ID = 7303

/** Etiqueta corta de una cámara («Trasera · 50 MP», «Frontal», «Infrarroja»…). */
fun cameraLabel(ctx: Context, c: uniffi.kushare_mobile.MobileCamera): String {
    val side =
        when (c.facing) {
            "front" -> ctx.getString(R.string.camera_front)
            "external" -> ctx.getString(R.string.camera_external)
            else -> ctx.getString(R.string.camera_back)
        }
    val extra =
        when {
            c.infrared -> ctx.getString(R.string.camera_infrared)
            c.monochrome -> ctx.getString(R.string.camera_mono)
            c.logical -> ctx.getString(R.string.camera_logical)
            else -> null
        }
    return listOfNotNull(side, "%.0f MP".format(c.megapixels).takeIf { c.megapixels > 0 }, extra).joinToString(" · ")
}

/** El PC pidió escanear: abrir el escáner (o avisar con una notificación). */
object QrScan {
    fun open(ctx: Context) {
        val app = ctx.applicationContext
        val intent = Intent(app, QrScanActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Settings.canDrawOverlays(app)) {
            runCatching { app.startActivity(intent) }.onSuccess { return }
        }
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, app.getString(R.string.qr_title), NotificationManager.IMPORTANCE_HIGH))
        if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val pi = PendingIntent.getActivity(app, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(
            NOTIFICATION_ID,
            Notification
                .Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(app.getString(R.string.qr_request_title))
                .setContentText(app.getString(R.string.qr_request_text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build(),
        )
    }
}

/**
 * Escáner de QR y códigos de barras (ZXing, sin servicios de Google). Lo
 * leído se manda a los PC conectados; aquí se puede abrir o copiar.
 */
class QrScanActivity : ComponentActivity() {
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var camera: Camera? = null
    private var cameraId = mutableStateOf("")
    private var result = mutableStateOf<Pair<String, String>?>(null)
    private var granted = mutableStateOf(false)

    private val permission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            granted.value = ok
            if (!ok) finish()
        }

    private val reader =
        MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to
                        listOf(
                            BarcodeFormat.QR_CODE,
                            BarcodeFormat.DATA_MATRIX,
                            BarcodeFormat.AZTEC,
                            BarcodeFormat.PDF_417,
                            BarcodeFormat.EAN_13,
                            BarcodeFormat.EAN_8,
                            BarcodeFormat.UPC_A,
                            BarcodeFormat.CODE_128,
                        ),
                    DecodeHintType.TRY_HARDER to true,
                ),
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        granted.value = PhoneCameras.hasPermission(this)
        if (!granted.value) permission.launch(Manifest.permission.CAMERA)
        val cameras = PhoneCameras.list(this).filter { it.facing == "back" || it.facing == "front" }
        setContent {
            KushareTheme {
                Surface(Modifier.fillMaxSize(), color = Color.Black) {
                    val ok by granted
                    val found by result
                    val selected by cameraId
                    Box(Modifier.fillMaxSize()) {
                        if (ok && found == null) {
                            // Clave por cámara: al cambiarla se vuelve a enlazar.
                            androidx.compose.runtime.key(selected) { CameraPreview(selected) }
                        }
                        Column(
                            Modifier.fillMaxWidth().align(Alignment.TopCenter).statusBarsPadding().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(stringResource(R.string.qr_hint), color = Color.White, style = MaterialTheme.typography.titleMedium)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (c in cameras) {
                                    FilterChip(
                                        selected = selected == c.id || (selected.isEmpty() && c == cameras.firstOrNull()),
                                        onClick = { cameraId.value = c.id },
                                        label = { Text(cameraLabel(this@QrScanActivity, c)) },
                                    )
                                }
                            }
                            var torch by remember { mutableStateOf(false) }
                            OutlinedButton(onClick = {
                                torch = !torch
                                camera?.cameraControl?.enableTorch(torch)
                            }) { Text(stringResource(if (torch) R.string.qr_torch_off else R.string.qr_torch_on), color = Color.White) }
                        }
                        found?.let { (text, format) -> ResultCard(text, format, Modifier.align(Alignment.BottomCenter)) }
                    }
                }
            }
        }
    }

    @Composable
    private fun CameraPreview(id: String) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).also { view -> bind(view, id) }
            },
        )
    }

    private fun bind(view: PreviewView, id: String) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val analysis =
                ImageAnalysis
                    .Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
            analysis.setAnalyzer(analysisExecutor) { image ->
                image.use {
                    if (result.value != null) return@use
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    val source =
                        PlanarYUVLuminanceSource(data, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
                    val decoded =
                        try {
                            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
                        } catch (_: NotFoundException) {
                            null
                        } catch (_: Exception) {
                            null
                        } finally {
                            reader.reset()
                        }
                    if (decoded != null) runOnUiThread { onDecoded(decoded.text, decoded.barcodeFormat.name) }
                }
            }
            runCatching {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, PhoneCameras.selector(id), preview, analysis)
            }.onFailure { if (id.isNotEmpty()) cameraId.value = "" }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onDecoded(text: String, format: String) {
        if (result.value != null) return
        result.value = text to format
        runCatching {
            getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
        }
        KushareLink.service?.sendQrResult(text, format, System.currentTimeMillis())
    }

    @Composable
    private fun ResultCard(text: String, format: String, modifier: Modifier) {
        val isUrl = text.startsWith("http://", true) || text.startsWith("https://", true)
        val sent = KushareLink.service != null
        Surface(
            modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(format.replace('_', ' '), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 6)
                Text(
                    stringResource(if (sent) R.string.qr_sent else R.string.qr_not_sent),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.background(Color.Transparent),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isUrl) {
                        Button(onClick = {
                            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(text))) }
                        }) { Text(stringResource(R.string.qr_open)) }
                    }
                    FilledTonalButton(onClick = {
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("QR", text))
                    }) { Text(stringResource(R.string.qr_copy)) }
                    OutlinedButton(onClick = { result.value = null }) { Text(stringResource(R.string.qr_again)) }
                }
            }
        }
    }

    override fun onDestroy() {
        analysisExecutor.shutdown()
        super.onDestroy()
    }
}
