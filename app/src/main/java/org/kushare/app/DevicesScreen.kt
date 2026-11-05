package org.kushare.app

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.kushare_mobile.MobileApp
import uniffi.kushare_mobile.MobilePeer

private fun bluetoothHelper(context: Context): BluetoothHelper? =
    context.getSystemService(BluetoothManager::class.java)?.adapter?.let { BluetoothHelper(it) }

private fun notificationAccessGranted(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

/** Qué filtro de apps se está editando. */
private data class AppFilterTarget(val peer: MobilePeer, val outgoing: Boolean)

@Composable
fun DevicesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val peers by KushareLink.peers.collectAsState()
    val discovered by KushareLink.discovered.collectAsState()
    val pairing by KushareLink.pendingPairing.collectAsState()
    val message by KushareLink.messages.collectAsState()
    var accessGranted by remember { mutableStateOf(notificationAccessGranted(context)) }
    var filterTarget by remember { mutableStateOf<AppFilterTarget?>(null) }
    var pairingInProgress by remember { mutableStateOf<String?>(null) }
    val bonded = remember { mutableStateListOf<BondedPeer>() }
    LaunchedEffect(Unit) {
        val list = runCatching { bluetoothHelper(context)?.bondedPeers() }.getOrNull().orEmpty()
        bonded.clear()
        bonded.addAll(list)
    }

    // Volver de los ajustes del sistema: refrescar el estado del permiso.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    accessGranted = notificationAccessGranted(context)
                    KushareLink.refreshPeers()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val pairedIds = peers.map { it.deviceId }.toSet()
    val visible = discovered.values.filter { it.deviceId !in pairedIds }

    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Dispositivos", style = MaterialTheme.typography.headlineSmall)
            message?.let { Text(it, modifier = Modifier.padding(vertical = 4.dp)) }
        }
        if (!accessGranted) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Para enviar las notificaciones del teléfono, kushare necesita acceso a ellas.")
                        Button(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        ) { Text("Permitir acceso a notificaciones") }
                    }
                }
            }
        }

        item { Text("Emparejados", style = MaterialTheme.typography.titleMedium) }
        if (peers.isEmpty()) item { Text("(ninguno)") }
        items(peers, key = { it.deviceId }) { peer ->
            PeerCard(
                peer = peer,
                onDirection = { outgoing, enabled ->
                    runCatching { KushareLink.service?.setDirection(peer.deviceId, outgoing, enabled) }
                    KushareLink.refreshPeers()
                },
                onApps = { outgoing -> filterTarget = AppFilterTarget(peer, outgoing) },
                onUnpair = {
                    runCatching { KushareLink.service?.unpair(peer.deviceId) }
                    KushareLink.refreshPeers()
                },
            )
        }

        item { Text("Visibles en la red", style = MaterialTheme.typography.titleMedium) }
        if (visible.isEmpty()) item { Text("(buscando…)") }
        items(visible, key = { it.deviceId }) { sighting ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(sighting.name)
                    Text("${sighting.host}:${sighting.port}", style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    enabled = pairingInProgress == null,
                    onClick = {
                        pairingInProgress = sighting.deviceId
                        scope.launch {
                            try {
                                KushareLink.ensure(context).pairTcp(sighting.host, sighting.port.toUShort())
                            } catch (e: Exception) {
                                KushareLink.messages.value = "No se pudo emparejar: ${e.message}"
                            } finally {
                                pairingInProgress = null
                                KushareLink.refreshPeers()
                            }
                        }
                    },
                ) { Text(if (pairingInProgress == sighting.deviceId) "Esperando…" else "Emparejar") }
            }
        }

        // Redes que aíslan a los clientes (Wi-Fi de oficina, universidad,
        // hoteles) no dejan que el teléfono y el PC se vean: ahí se empareja
        // por Bluetooth, con un dispositivo ya vinculado en los ajustes.
        item {
            Text("Emparejar por Bluetooth", style = MaterialTheme.typography.titleMedium)
            Text(
                "Para redes donde los dispositivos no se ven entre sí. El PC debe estar vinculado por Bluetooth.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val pairedBt = peers.mapNotNull { it.btAddress }.toSet()
        val bondedCandidates = bonded.filter { it.address !in pairedBt }
        if (bondedCandidates.isEmpty()) item { Text("(ningún dispositivo Bluetooth vinculado)") }
        items(bondedCandidates, key = { "bt-" + it.address }) { device ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(device.name)
                    Text(device.address, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    enabled = pairingInProgress == null,
                    onClick = {
                        pairingInProgress = device.address
                        scope.launch {
                            try {
                                val helper = bluetoothHelper(context) ?: error("sin Bluetooth")
                                val io = withContext(Dispatchers.IO) { helper.connectLink(device.address) }
                                KushareLink.ensure(context).pairBt(io, device.address)
                            } catch (e: Exception) {
                                KushareLink.messages.value =
                                    "No se pudo emparejar con ${device.name}: ${e.message} " +
                                    "(¿está kushare corriendo en ese equipo?)"
                            } finally {
                                pairingInProgress = null
                                KushareLink.refreshPeers()
                            }
                        }
                    },
                ) { Text(if (pairingInProgress == device.address) "Esperando…" else "Emparejar") }
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Volver") }
        }
    }

    pairing?.let { prompt ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Emparejar con ${prompt.deviceName}") },
            text = {
                Column {
                    Text("Comprueba que el otro dispositivo muestra el mismo código:")
                    Text(prompt.pin, style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = { KushareLink.respondPairing(prompt.deviceId, true) }) { Text("Coincide") }
            },
            dismissButton = {
                TextButton(onClick = { KushareLink.respondPairing(prompt.deviceId, false) }) { Text("Rechazar") }
            },
        )
    }

    filterTarget?.let { target ->
        AppFilterDialog(target = target, onDismiss = { filterTarget = null })
    }
}

@Composable
private fun PeerCard(
    peer: MobilePeer,
    onDirection: (outgoing: Boolean, enabled: Boolean) -> Unit,
    onApps: (outgoing: Boolean) -> Unit,
    onUnpair: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(peer.deviceName, style = MaterialTheme.typography.titleMedium)
            Text(
                if (peer.connected) "Conectado por ${peer.transport ?: "?"}" else "Desconectado",
                style = MaterialTheme.typography.bodySmall,
            )
            ToggleRow("Enviar mis notificaciones", peer.outgoingEnabled) { onDirection(true, it) }
            ToggleRow("Recibir sus notificaciones", peer.incomingEnabled) { onDirection(false, it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onApps(true) }) { Text("Apps que envío") }
                OutlinedButton(onClick = { onApps(false) }) { Text("Apps que recibo") }
            }
            TextButton(onClick = onUnpair) { Text("Desemparejar") }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun AppFilterDialog(target: AppFilterTarget, onDismiss: () -> Unit) {
    val peers by KushareLink.peers.collectAsState()
    // Leer la versión más reciente del peer (cambia al marcar casillas).
    val peer = peers.firstOrNull { it.deviceId == target.peer.deviceId } ?: target.peer
    val apps: List<MobileApp> =
        if (target.outgoing) KushareLink.service?.localApps().orEmpty() else peer.peerApps
    val blocked = if (target.outgoing) peer.outgoingBlocked else peer.incomingBlocked

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (target.outgoing) "Apps que envío a ${peer.deviceName}" else "Apps que recibo de ${peer.deviceName}") },
        text = {
            if (apps.isEmpty()) {
                Text("Todavía no se ha visto ninguna notificación. Las apps aparecen aquí cuando notifican algo.")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(apps, key = { it.appId }) { app ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = app.appId !in blocked,
                                onCheckedChange = { allowed ->
                                    runCatching {
                                        KushareLink.service?.setAppBlocked(peer.deviceId, target.outgoing, app.appId, !allowed)
                                    }
                                    KushareLink.refreshPeers()
                                },
                            )
                            Text(app.name)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Listo") } },
    )
}
