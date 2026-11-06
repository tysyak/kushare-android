package org.kushare.app

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kushare.app.ui.Appearance
import org.kushare.app.ui.DevicesScreen
import org.kushare.app.ui.HomeScreen
import org.kushare.app.ui.KushareTheme
import org.kushare.app.ui.ReceiveScreen
import org.kushare.app.ui.SendScreen
import org.kushare.app.ui.SettingsScreen
import org.kushare.app.ui.ThemeSettings
import org.kushare.app.ui.currentAppearance
import uniffi.kushare_mobile.ReceiveCallback
import uniffi.kushare_mobile.ReceiverHandle
import uniffi.kushare_mobile.SendProgressCallback
import uniffi.kushare_mobile.randomEndpointId
import uniffi.kushare_mobile.sendFile
import uniffi.kushare_mobile.sendFileBt
import uniffi.kushare_mobile.startReceiver

private sealed class Screen(val depth: Int) {
    data object Home : Screen(0)

    data object Receive : Screen(1)

    data class Send(val filePath: String? = null, val fileName: String? = null) : Screen(1)

    data object Devices : Screen(1)

    data object Settings : Screen(1)
}

class MainActivity : ComponentActivity() {
    companion object {
        /** Abrir directamente la pantalla de dispositivos (desde una notificación). */
        const val EXTRA_OPEN_DEVICES = "open_devices"
    }

    private val openDevices = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeSettings.load(this)
        openDevices.value = intent?.getBooleanExtra(EXTRA_OPEN_DEVICES, false) == true
        KushareLink.attach(this)
        KushareSwitch.isEnabled(this)
        KushareService.start(this) // respeta el interruptor del usuario
        enableEdgeToEdge()
        setContent {
            KushareTheme {
                SystemBarsFollowTheme(this)
                KushareApp(activity = this, openDevices = openDevices)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_DEVICES, false)) openDevices.value = true
    }
}

/** Iconos de las barras del sistema claros u oscuros según el tema de la app (no el del sistema). */
@Composable
private fun SystemBarsFollowTheme(activity: ComponentActivity) {
    val config by ThemeSettings.config.collectAsState()
    val dark = currentAppearance(config) != Appearance.LIGHT
    DisposableEffect(dark) {
        val style =
            if (dark) {
                SystemBarStyle.dark(Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            }
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        onDispose {}
    }
}

@Composable
private fun KushareApp(activity: ComponentActivity, openDevices: MutableState<Boolean>) {
    val nsdHelper = remember { NsdHelper(activity) }
    val bluetoothHelper =
        remember {
            val manager = activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            BluetoothHelper(manager.adapter)
        }
    val endpointId = remember { randomEndpointId() }
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var deviceName by remember { mutableStateOf(KushareLink.deviceName(activity)) }
    var visible by remember { mutableStateOf(false) }
    var receiverHandle by remember { mutableStateOf<ReceiverHandle?>(null) }
    val activityLog = remember { mutableStateListOf<String>() }
    val devices = remember { mutableStateListOf<DiscoveredPeer>() }
    val bondedPeers = remember { mutableStateListOf<BondedPeer>() }
    var sending by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var resultMessage by remember { mutableStateOf<String?>(null) }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    fun ensurePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
            needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            needed.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        val missing = needed.filter { activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    LaunchedEffect(Unit) { ensurePermissions() }
    LaunchedEffect(openDevices.value) {
        if (openDevices.value) {
            openDevices.value = false
            screen = Screen.Devices
        }
    }

    suspend fun ensureReceiverStarted(): ReceiverHandle {
        receiverHandle?.let { return it }
        val downloadDir =
            activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(activity.filesDir, "Download").apply { mkdirs() }
        val callback =
            object : ReceiveCallback {
                override fun onIncomingRequest(deviceName: String, fileName: String, fileSize: ULong) {
                    activityLog.add(0, "↓ $deviceName quiere enviar \"$fileName\" ($fileSize bytes)")
                }

                override fun onCompleted(deviceName: String, fileName: String, savedTo: String) {
                    activityLog.add(0, "✓ recibido \"$fileName\" de $deviceName")
                }

                override fun onFailed(fileName: String, reason: String) {
                    activityLog.add(0, "✗ error con \"$fileName\": $reason")
                }
            }
        val handle = startReceiver(0u, downloadDir.absolutePath, callback)
        receiverHandle = handle
        return handle
    }

    // Recibir por Wi-Fi: anunciarse por mDNS mientras la pantalla está
    // abierta. Por Bluetooth recibe siempre el servicio de kushare.
    fun startVisibility() {
        ensurePermissions()
        scope.launch {
            val handle = ensureReceiverStarted()
            nsdHelper.advertise(endpointId, deviceName, handle.localPort().toInt())
            visible = true
        }
    }

    fun stopVisibility() {
        nsdHelper.stopAdvertising()
        visible = false
    }

    fun startScanningForSend() {
        ensurePermissions()
        devices.clear()
        nsdHelper.discover { peer ->
            activity.runOnUiThread {
                if (devices.none { it.host == peer.host && it.port == peer.port }) devices.add(peer)
            }
        }
        bondedPeers.clear()
        runCatching { bondedPeers.addAll(bluetoothHelper.bondedPeers()) }
    }

    suspend fun copyUriToCache(uri: Uri): Pair<String, String> =
        withContext(Dispatchers.IO) {
            val name =
                activity.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
                } ?: "archivo"
            val dest = File(activity.cacheDir, name)
            activity.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.absolutePath to name
        }

    val filePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val (path, name) = copyUriToCache(uri)
                resultMessage = null
                screen = Screen.Send(filePath = path, fileName = name)
                startScanningForSend()
            }
        }

    val progressCallback =
        object : SendProgressCallback {
            override fun onProgress(bytesTransferred: ULong, totalBytes: ULong) {
                progress = bytesTransferred.toLong() to totalBytes.toLong()
            }
        }

    fun runSend(okMessage: String, block: suspend () -> Unit) {
        sending = true
        progress = 0L to 0L
        resultMessage = null
        scope.launch {
            try {
                block()
                resultMessage = okMessage
            } catch (e: Exception) {
                resultMessage = activity.getString(R.string.send_error, e.message)
            } finally {
                sending = false
            }
        }
    }

    fun goHome() {
        when (screen) {
            is Screen.Receive -> stopVisibility()
            is Screen.Send -> nsdHelper.stopDiscovery()
            else -> {}
        }
        screen = Screen.Home
    }

    BackHandler(enabled = screen != Screen.Home && !sending) { goHome() }

    // Transición entre pantallas con los muelles del esquema expresivo:
    // entrar desliza desde la derecha; volver, desde la izquierda.
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            val forward = targetState.depth >= initialState.depth
            val dir = if (forward) 1 else -1
            (slideInHorizontally(spatial) { dir * it / 3 } + fadeIn(effects)) togetherWith
                (slideOutHorizontally(spatial) { -dir * it / 6 } + fadeOut(effects))
        },
        contentKey = { it::class },
        label = "screens",
    ) { current ->
        when (current) {
            is Screen.Home ->
                HomeScreen(
                    deviceName = deviceName,
                    onSend = { filePicker.launch(arrayOf("*/*")) },
                    onReceive = {
                        screen = Screen.Receive
                        startVisibility()
                    },
                    onDevices = {
                        ensurePermissions()
                        screen = Screen.Devices
                    },
                    onSettings = { screen = Screen.Settings },
                )
            is Screen.Devices -> DevicesScreen(onBack = ::goHome)
            is Screen.Settings -> SettingsScreen(onBack = ::goHome)
            is Screen.Receive ->
                ReceiveScreen(
                    deviceName = deviceName,
                    onDeviceNameChange = { deviceName = it },
                    visible = visible,
                    onVisibleChange = { on -> if (on) startVisibility() else stopVisibility() },
                    log = activityLog,
                    onBack = ::goHome,
                )
            is Screen.Send ->
                SendScreen(
                    fileName = current.fileName,
                    devices = devices,
                    bondedPeers = bondedPeers,
                    sending = sending,
                    progress = progress,
                    resultMessage = resultMessage,
                    onSend = { peer ->
                        current.filePath?.let { path ->
                            runSend(activity.getString(R.string.send_done_to, peer.name)) {
                                sendFile("${peer.host}:${peer.port}", endpointId, deviceName, path, progressCallback)
                            }
                        }
                    },
                    onSendBt = { peer ->
                        current.filePath?.let { path ->
                            runSend(activity.getString(R.string.send_done_bt, peer.name)) {
                                val io = withContext(Dispatchers.IO) { bluetoothHelper.connect(peer.address) }
                                sendFileBt(io, endpointId, deviceName, path, progressCallback)
                            }
                        }
                    },
                    onManualSend = { host, port ->
                        current.filePath?.let { path ->
                            runSend(activity.getString(R.string.send_done_to, "$host:$port")) { sendFile("$host:$port", endpointId, deviceName, path, progressCallback) }
                        }
                    },
                    onBack = ::goHome,
                )
        }
    }
}
