package org.kushare.app

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.kushare_mobile.ReceiveCallback
import uniffi.kushare_mobile.ReceiverHandle
import uniffi.kushare_mobile.SendProgressCallback
import uniffi.kushare_mobile.randomEndpointId
import uniffi.kushare_mobile.receiveOverBt
import uniffi.kushare_mobile.sendFile
import uniffi.kushare_mobile.sendFileBt
import uniffi.kushare_mobile.startReceiver

private sealed class Screen {
    data object Home : Screen()

    data object Receive : Screen()

    data class Send(val filePath: String? = null, val fileName: String? = null) : Screen()

    data object Devices : Screen()
}

class MainActivity : ComponentActivity() {
    companion object {
        /** Abrir directamente la pantalla de dispositivos (desde una notificación). */
        const val EXTRA_OPEN_DEVICES = "open_devices"
    }

    private val openDevices = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openDevices.value = intent?.getBooleanExtra(EXTRA_OPEN_DEVICES, false) == true
        KushareLink.ensure(this)
        KushareService.start(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    KushareApp(activity = this, openDevices = openDevices)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_DEVICES, false)) openDevices.value = true
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
    var deviceName by remember { mutableStateOf(Build.MODEL ?: "kushare-android") }
    var visible by remember { mutableStateOf(false) }
    var receiverHandle by remember { mutableStateOf<ReceiverHandle?>(null) }
    var btServerSocket by remember { mutableStateOf<BluetoothServerSocket?>(null) }
    val activityLog = remember { mutableStateListOf<String>() }
    val devices = remember { mutableStateListOf<DiscoveredPeer>() }
    val bondedPeers = remember { mutableStateListOf<BondedPeer>() }
    var sending by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var resultMessage by remember { mutableStateOf<String?>(null) }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    LaunchedEffect(Unit) { ensurePermissionsOnStart(activity, permissionLauncher::launch) }

    LaunchedEffect(openDevices.value) {
        if (openDevices.value) {
            openDevices.value = false
            screen = Screen.Devices
        }
    }

    fun ensurePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            needed.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        val missing = needed.filter { activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
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
                    activityLog.add(0, "✓ recibido \"$fileName\" de $deviceName -> $savedTo")
                }

                override fun onFailed(fileName: String, reason: String) {
                    activityLog.add(0, "✗ error con \"$fileName\": $reason")
                }
            }
        val handle = startReceiver(0u, downloadDir.absolutePath, callback)
        receiverHandle = handle
        return handle
    }

    fun receiveCallbackFor(tag: String) =
        object : ReceiveCallback {
            override fun onIncomingRequest(deviceName: String, fileName: String, fileSize: ULong) {
                activityLog.add(0, "↓$tag $deviceName quiere enviar \"$fileName\" ($fileSize bytes)")
            }

            override fun onCompleted(deviceName: String, fileName: String, savedTo: String) {
                activityLog.add(0, "✓$tag recibido \"$fileName\" de $deviceName -> $savedTo")
            }

            override fun onFailed(fileName: String, reason: String) {
                activityLog.add(0, "✗$tag error con \"$fileName\": $reason")
            }
        }

    /** Escucha conexiones RFCOMM entrantes en un bucle bloqueante (ver
     * BluetoothHelper.listen) hasta que `btServerSocket` se cierre desde
     * [stopVisibility]. Cada conexión aceptada se atiende en su propia
     * corrutina para no bloquear la aceptación de la siguiente. */
    suspend fun startBluetoothReceiving() {
        withContext(Dispatchers.IO) {
            val downloadDir =
                activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: File(activity.filesDir, "Download").apply { mkdirs() }
            val serverSocket =
                try {
                    bluetoothHelper.listen()
                } catch (e: Exception) {
                    activityLog.add(0, "✗ no se pudo escuchar por Bluetooth: ${e.message}")
                    return@withContext
                }
            btServerSocket = serverSocket
            while (true) {
                val socket =
                    try {
                        serverSocket.accept()
                    } catch (e: Exception) {
                        break
                    }
                scope.launch(Dispatchers.IO) {
                    try {
                        receiveOverBt(BluetoothSocketIo(socket), downloadDir.absolutePath, receiveCallbackFor(" (BT)"))
                    } catch (e: Exception) {
                        activityLog.add(0, "✗ (BT) conexión falló: ${e.message}")
                    }
                }
            }
        }
    }

    fun startVisibility() {
        ensurePermissions()
        scope.launch {
            val handle = ensureReceiverStarted()
            nsdHelper.advertise(endpointId, deviceName, handle.localPort().toInt())
            visible = true
        }
        scope.launch { startBluetoothReceiving() }
    }

    fun stopVisibility() {
        nsdHelper.stopAdvertising()
        btServerSocket?.let { runCatching { it.close() } }
        btServerSocket = null
        visible = false
    }

    fun startScanningForSend() {
        ensurePermissions()
        devices.clear()
        nsdHelper.discover { peer ->
            activity.runOnUiThread {
                if (devices.none { it.host == peer.host && it.port == peer.port }) {
                    devices.add(peer)
                }
            }
        }
        bondedPeers.clear()
        try {
            bondedPeers.addAll(bluetoothHelper.bondedPeers())
        } catch (e: SecurityException) {
            activityLog.add(0, "✗ sin permiso de Bluetooth para listar emparejados: ${e.message}")
        }
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
                screen = Screen.Send(filePath = path, fileName = name)
                startScanningForSend()
            }
        }

    fun sendTo(peer: DiscoveredPeer, filePath: String) {
        sending = true
        progress = 0L to 0L
        resultMessage = null
        scope.launch {
            val callback =
                object : SendProgressCallback {
                    override fun onProgress(bytesTransferred: ULong, totalBytes: ULong) {
                        progress = bytesTransferred.toLong() to totalBytes.toLong()
                    }
                }
            try {
                sendFile("${peer.host}:${peer.port}", endpointId, deviceName, filePath, callback)
                resultMessage = "Archivo enviado."
            } catch (e: Exception) {
                resultMessage = "Error: ${e.message}"
            } finally {
                sending = false
            }
        }
    }

    fun sendToBt(peer: BondedPeer, filePath: String) {
        sending = true
        progress = 0L to 0L
        resultMessage = null
        scope.launch {
            val callback =
                object : SendProgressCallback {
                    override fun onProgress(bytesTransferred: ULong, totalBytes: ULong) {
                        progress = bytesTransferred.toLong() to totalBytes.toLong()
                    }
                }
            try {
                val io = withContext(Dispatchers.IO) { bluetoothHelper.connect(peer.address) }
                sendFileBt(io, endpointId, deviceName, filePath, callback)
                resultMessage = "Archivo enviado por Bluetooth."
            } catch (e: Exception) {
                resultMessage = "Error (Bluetooth): ${e.message}"
            } finally {
                sending = false
            }
        }
    }

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            when (val current = screen) {
                is Screen.Devices -> DevicesScreen(onBack = { screen = Screen.Home })
                is Screen.Home ->
                    HomeScreen(
                        onDevices = {
                            ensurePermissions()
                            screen = Screen.Devices
                        },
                        onSend = { filePicker.launch(arrayOf("*/*")) },
                        onReceive = {
                            screen = Screen.Receive
                            startVisibility()
                        },
                    )
                is Screen.Receive ->
                    ReceiveScreen(
                        deviceName = deviceName,
                        onDeviceNameChange = { deviceName = it },
                        visible = visible,
                        onVisibleChange = { on -> if (on) startVisibility() else stopVisibility() },
                        log = activityLog,
                        onBack = {
                            stopVisibility()
                            screen = Screen.Home
                        },
                    )
                is Screen.Send ->
                    SendScreen(
                        fileName = current.fileName,
                        devices = devices,
                        bondedPeers = bondedPeers,
                        sending = sending,
                        progress = progress,
                        resultMessage = resultMessage,
                        onSend = { peer -> current.filePath?.let { sendTo(peer, it) } },
                        onSendBt = { peer -> current.filePath?.let { sendToBt(peer, it) } },
                        onManualSend = { host, port ->
                            current.filePath?.let {
                                sendTo(DiscoveredPeer(name = host, host = host, port = port), it)
                            }
                        },
                        onBack = {
                            nsdHelper.stopDiscovery()
                            resultMessage = null
                            screen = Screen.Home
                        },
                    )
            }
        }
    }
}

@Composable
private fun HomeScreen(onDevices: () -> Unit, onSend: () -> Unit, onReceive: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("kushare", style = MaterialTheme.typography.headlineMedium)
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
        Button(onClick = onSend, modifier = Modifier.fillMaxWidth()) { Text("Enviar") }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(4.dp))
        Button(onClick = onReceive, modifier = Modifier.fillMaxWidth()) { Text("Recibir") }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(4.dp))
        Button(onClick = onDevices, modifier = Modifier.fillMaxWidth()) { Text("Dispositivos y notificaciones") }
    }
}

@Composable
private fun ReceiveScreen(
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    visible: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    log: List<String>,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text("Recibir", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = deviceName,
            onValueChange = onDeviceNameChange,
            label = { Text("Nombre del dispositivo") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (visible) "Visible para todos" else "Oculto")
            Switch(checked = visible, onCheckedChange = onVisibleChange)
        }
        Text("Actividad", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(log) { line -> Text(line, modifier = Modifier.padding(vertical = 2.dp)) }
        }
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Volver") }
    }
}

@Composable
private fun SendScreen(
    fileName: String?,
    devices: List<DiscoveredPeer>,
    bondedPeers: List<BondedPeer>,
    sending: Boolean,
    progress: Pair<Long, Long>?,
    resultMessage: String?,
    onSend: (DiscoveredPeer) -> Unit,
    onSendBt: (BondedPeer) -> Unit,
    onManualSend: (host: String, port: Int) -> Unit,
    onBack: () -> Unit,
) {
    var manualAddress by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        Text("Enviar", style = MaterialTheme.typography.headlineSmall)
        Text("Archivo: ${fileName ?: "(ninguno)"}")

        if (resultMessage != null) {
            Text(resultMessage, modifier = Modifier.padding(vertical = 8.dp))
        } else if (sending) {
            val (bytes, total) = progress ?: (0L to 0L)
            val ratio = if (total > 0) bytes.toFloat() / total.toFloat() else 0f
            Text("Enviando... $bytes/$total bytes")
            LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth())
        } else {
            Text(
                "Dispositivos cercanos",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(devices) { peer ->
                    Button(
                        onClick = { onSend(peer) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) { Text("${peer.name}  [${peer.host}:${peer.port}]") }
                }
            }

            // Bluetooth RFCOMM clásico, como alternativa a la red IP:
            // kushare asume que los dispositivos ya están emparejados
            // (igual que el lado Linux, ver BluetoothHelper.kt), así que
            // solo se listan los ya emparejados, no un escaneo activo.
            Text(
                "Bluetooth (emparejados)",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(bondedPeers) { peer ->
                    Button(
                        onClick = { onSendBt(peer) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) { Text("${peer.name}  [${peer.address}]") }
                }
            }

            // El descubrimiento por mDNS (NsdManager) no funciona en redes
            // con aislamiento de clientes o en el hotspot de Android (no
            // reenvía multicast) — esta entrada manual es el respaldo.
            Text(
                "Conectar manualmente",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            OutlinedTextField(
                value = manualAddress,
                onValueChange = { manualAddress = it },
                label = { Text("IP:puerto, p. ej. 172.19.65.37:42713") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            Button(
                onClick = {
                    val (host, portText) = manualAddress.split(":", limit = 2).let {
                        it.getOrElse(0) { "" } to it.getOrNull(1)
                    }
                    val port = portText?.toIntOrNull()
                    if (host.isNotBlank() && port != null) onManualSend(host, port)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Enviar a esta dirección") }
        }

        Button(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Volver") }
    }
}

/** Al abrir la app: notificaciones (para los espejos del PC) y Bluetooth (para el enlace RFCOMM). */
private fun ensurePermissionsOnStart(activity: ComponentActivity, launch: (Array<String>) -> Unit) {
    val needed = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) needed.add(Manifest.permission.POST_NOTIFICATIONS)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
    val missing = needed.filter { activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    if (missing.isNotEmpty()) launch(missing.toTypedArray())
}
