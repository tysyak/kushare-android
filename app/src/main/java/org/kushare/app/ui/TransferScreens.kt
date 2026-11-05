package org.kushare.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import org.kushare.app.BondedPeer
import org.kushare.app.DiscoveredPeer

@Composable
private fun TransferScaffold(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title, style = MaterialTheme.typography.displaySmallEmphasized) },
                subtitle = { Text(subtitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Volver") }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun ReceiveScreen(
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    visible: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    log: List<String>,
    onBack: () -> Unit,
) {
    TransferScaffold("Recibir", if (visible) "Visible en la red" else "Oculto", onBack) {
        item {
            ExpressiveCard(
                shape = KushareShapes.Hero,
                containerColor = if (visible) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (visible) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShapeIcon(
                        icon = Icons.Rounded.Download,
                        polygon = MaterialShapes.Flower,
                        container = if (visible) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant,
                        content = if (visible) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 64.dp,
                        active = visible,
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(if (visible) "Visible" else "Oculto", style = MaterialTheme.typography.headlineMediumEmphasized)
                        Text(
                            "Por Wi-Fi mientras esta pantalla está abierta. Por Bluetooth se recibe siempre que kushare está encendido.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                SwitchRow("Visible para todos", visible, onVisibleChange, modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = onDeviceNameChange,
                    label = { Text("Nombre del dispositivo") },
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
        item { SectionTitle("Actividad") }
        if (log.isEmpty()) {
            item {
                Text(
                    "Los archivos recibidos se guardan en Descargas.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        items(log) { line ->
            ExpressiveCard(shape = MaterialTheme.shapes.large, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Text(line, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun SendScreen(
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
    TransferScaffold("Enviar", fileName ?: "Ningún archivo", onBack) {
        item {
            ExpressiveCard(
                shape = KushareShapes.ActionLeading,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShapeIcon(
                        icon = if (resultMessage != null && !sending) Icons.Rounded.CheckCircle else Icons.AutoMirrored.Rounded.InsertDriveFile,
                        polygon = MaterialShapes.Clover4Leaf,
                        container = MaterialTheme.colorScheme.tertiary,
                        content = MaterialTheme.colorScheme.onTertiary,
                        size = 64.dp,
                        active = sending,
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(fileName ?: "—", style = MaterialTheme.typography.titleLargeEmphasized, maxLines = 2)
                        AnimatedContent(targetState = Triple(sending, resultMessage, progress), label = "sendState") { (isSending, result, prog) ->
                            when {
                                isSending -> {
                                    val (bytes, total) = prog ?: (0L to 0L)
                                    Column {
                                        Text("Enviando… ${bytes / 1024} / ${total / 1024} KB", style = MaterialTheme.typography.bodyMedium)
                                        LinearWavyProgressIndicator(
                                            progress = { if (total > 0) bytes.toFloat() / total else 0f },
                                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                        )
                                    }
                                }
                                result != null -> Text(result, style = MaterialTheme.typography.bodyMediumEmphasized)
                                else -> Text("Elige a quién enviarlo", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
        if (!sending) {
            item { SectionTitle("Cerca, por Wi-Fi") }
            if (devices.isEmpty()) {
                item {
                    Text(
                        "Buscando… (no funciona si la red aísla a los dispositivos)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            items(devices) { peer ->
                TargetRow(peer.name, "${peer.host}:${peer.port}", Icons.Rounded.Wifi) { onSend(peer) }
            }
            item { SectionTitle("Por Bluetooth") }
            items(bondedPeers) { peer ->
                TargetRow(peer.name, peer.address, Icons.Rounded.Bluetooth) { onSendBt(peer) }
            }
            item { SectionTitle("Dirección manual") }
            item {
                ExpressiveCard(shape = KushareShapes.ListCard, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    OutlinedTextField(
                        value = manualAddress,
                        onValueChange = { manualAddress = it },
                        label = { Text("IP:puerto") },
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.size(12.dp))
                    Button(
                        onClick = {
                            val parts = manualAddress.split(":", limit = 2)
                            val host = parts.getOrElse(0) { "" }
                            val port = parts.getOrNull(1)?.toIntOrNull()
                            if (host.isNotBlank() && port != null) onManualSend(host, port)
                        },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text("Enviar a esta dirección") }
                }
            }
        }
    }
}

@Composable
private fun TargetRow(name: String, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    ExpressiveCard(shape = KushareShapes.ListCard, containerColor = MaterialTheme.colorScheme.surfaceContainer, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShapeIcon(
                icon = icon,
                polygon = MaterialShapes.Square,
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
                size = 44.dp,
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMediumEmphasized)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
