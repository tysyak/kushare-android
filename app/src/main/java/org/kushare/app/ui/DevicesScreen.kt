package org.kushare.app.ui

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kushare.app.R
import org.kushare.app.friendlyError
import org.kushare.app.BluetoothHelper
import org.kushare.app.BondedPeer
import org.kushare.app.KushareLink
import org.kushare.app.KushareService
import org.kushare.app.KushareSwitch
import org.kushare.app.PeerExtras
import org.kushare.app.PhoneFeatures
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
    val scanning by KushareLink.scanning.collectAsState()
    val enabled by KushareSwitch.enabled.collectAsState()
    val paused by KushareSwitch.paused.collectAsState()
    val extras by PhoneFeatures.peerExtras.collectAsState()
    var accessGranted by remember { mutableStateOf(notificationAccessGranted(context)) }
    var filterTarget by remember { mutableStateOf<AppFilterTarget?>(null) }
    var pairingInProgress by remember { mutableStateOf<String?>(null) }
    val bonded = remember { mutableStateListOf<BondedPeer>() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

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
    val pairedBt = peers.mapNotNull { it.btAddress }.toSet()
    val bondedCandidates = bonded.filter { it.address !in pairedBt }
    val canUseLink = enabled && !paused

    fun pairWith(id: String, action: suspend () -> Unit) {
        pairingInProgress = id
        scope.launch {
            try {
                action()
            } catch (e: Exception) {
                KushareLink.messages.value = context.getString(R.string.devices_pair_failed, context.friendlyError(e))
            } finally {
                pairingInProgress = null
                KushareLink.refreshPeers()
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.devices_title), style = MaterialTheme.typography.displaySmallEmphasized) },
                subtitle = {
                    Text(
                        when {
                            !enabled -> stringResource(R.string.devices_off)
                            paused -> stringResource(R.string.devices_paused)
                            else -> stringResource(R.string.devices_connected_of, peers.count { it.connected }, peers.size)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            message?.let { text ->
                item(key = "message") {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { KushareLink.messages.value = null },
                    ) { Text(text, style = MaterialTheme.typography.bodyMediumEmphasized, modifier = Modifier.padding(16.dp)) }
                }
            }
            if (!canUseLink) {
                item(key = "off") {
                    ExpressiveCard(shape = KushareShapes.Hero, containerColor = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        SwitchRow(
                            title = stringResource(R.string.common_enabled),
                            subtitle = if (!enabled) stringResource(R.string.devices_enable_hint) else stringResource(R.string.devices_paused_hint),
                            checked = enabled,
                            onCheckedChange = { KushareSwitch.setEnabled(context, it) },
                        )
                    }
                }
            }
            if (!accessGranted) {
                item(key = "access") {
                    ExpressiveCard(
                        shape = KushareShapes.Wide,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ) {
                        Text(stringResource(R.string.devices_access_missing), style = MaterialTheme.typography.titleLargeEmphasized)
                        Text(stringResource(R.string.devices_access_missing_detail), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.size(12.dp))
                        Button(
                            onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                            shapes = ButtonDefaults.shapes(),
                        ) { Text(stringResource(R.string.devices_grant_access)) }
                    }
                }
            }

            item(key = "paired-title") { SectionTitle(stringResource(R.string.devices_paired)) }
            if (peers.isEmpty()) {
                item(key = "no-peers") {
                    Text(
                        stringResource(R.string.devices_none_yet),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            items(peers, key = { it.deviceId }) { peer ->
                PeerCard(
                    peer = peer,
                    extras = extras[peer.deviceId],
                    onDirection = { outgoing, on ->
                        runCatching { KushareLink.service?.setDirection(peer.deviceId, outgoing, on) }
                        KushareLink.refreshPeers()
                    },
                    onApps = { outgoing -> filterTarget = AppFilterTarget(peer, outgoing) },
                    onUnpair = {
                        runCatching { KushareLink.service?.unpair(peer.deviceId) }
                        KushareLink.refreshPeers()
                    },
                )
            }

            item(key = "net-title") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    SectionTitle(stringResource(R.string.devices_on_network), modifier = Modifier.weight(1f))
                    FilledTonalButton(
                        enabled = canUseLink && !scanning,
                        onClick = { KushareService.scan(context) },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        if (scanning) {
                            LoadingIndicator(modifier = Modifier.size(20.dp))
                        } else {
                            Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(if (scanning) stringResource(R.string.devices_searching) else stringResource(R.string.devices_search))
                    }
                }
            }
            if (visible.isEmpty()) {
                item(key = "no-visible") {
                    Text(
                        if (scanning) stringResource(R.string.devices_searching_detail)
                        else stringResource(R.string.devices_search_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            items(visible, key = { "net-" + it.deviceId }) { sighting ->
                CandidateRow(
                    name = sighting.name,
                    detail = "${sighting.host}:${sighting.port}",
                    icon = Icons.Rounded.Wifi,
                    busy = pairingInProgress == sighting.deviceId,
                    enabled = canUseLink && pairingInProgress == null,
                    onPair = {
                        pairWith(sighting.deviceId) {
                            val link = KushareLink.service ?: error("kushare está apagado o en pausa")
                            link.pairTcp(sighting.host, sighting.port.toUShort())
                        }
                    },
                )
            }

            item(key = "bt-title") { SectionTitle(stringResource(R.string.common_by_bluetooth)) }
            if (bondedCandidates.isEmpty()) {
                item(key = "no-bt") {
                    Text(
                        stringResource(R.string.devices_no_bonded),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            items(bondedCandidates, key = { "bt-" + it.address }) { device ->
                CandidateRow(
                    name = device.name,
                    detail = device.address,
                    icon = Icons.Rounded.Bluetooth,
                    busy = pairingInProgress == device.address,
                    enabled = canUseLink && pairingInProgress == null,
                    onPair = {
                        pairWith(device.address) {
                            val helper = bluetoothHelper(context) ?: error("sin Bluetooth")
                            val link = KushareLink.service ?: error("kushare está apagado o en pausa")
                            val io = withContext(Dispatchers.IO) { helper.connectLink(device.address) }
                            link.pairBt(io, device.address)
                        }
                    },
                )
            }
        }
    }

    pairing?.let { prompt ->
        AlertDialog(
            onDismissRequest = {},
            shape = KushareShapes.Dialog,
            icon = {
                ShapeIcon(
                    icon = Icons.Rounded.Computer,
                    polygon = MaterialShapes.Cookie9Sided,
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary,
                    active = true,
                )
            },
            title = { Text(stringResource(R.string.devices_pair_with, prompt.deviceName), style = MaterialTheme.typography.headlineSmallEmphasized) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.devices_pin_hint))
                    Surface(
                        shape = MaterialShapes.Pill.toShape(),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(top = 16.dp),
                    ) {
                        Text(
                            prompt.pin.chunked(3).joinToString(" "),
                            style = MaterialTheme.typography.displayMediumEmphasized,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { KushareLink.respondPairing(prompt.deviceId, true) }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.devices_pin_matches))
                }
            },
            dismissButton = {
                TextButton(onClick = { KushareLink.respondPairing(prompt.deviceId, false) }) { Text(stringResource(R.string.common_reject)) }
            },
        )
    }

    filterTarget?.let { target -> AppFilterDialog(target = target, onDismiss = { filterTarget = null }) }
}

@Composable
private fun CandidateRow(
    name: String,
    detail: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    busy: Boolean,
    enabled: Boolean,
    onPair: () -> Unit,
) {
    ExpressiveCard(shape = KushareShapes.ListCard, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShapeIcon(
                icon = icon,
                polygon = MaterialShapes.Square,
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
                size = 44.dp,
                active = busy,
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMediumEmphasized)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onPair, enabled = enabled, shapes = ButtonDefaults.shapes()) {
                Text(if (busy) stringResource(R.string.devices_waiting) else stringResource(R.string.devices_pair))
            }
        }
    }
}

/** Multimedia y salida de audio del PC, controlables desde el teléfono. */
@Composable
private fun PeerControls(deviceId: String, extras: PeerExtras) {
    val link = KushareLink.service ?: return
    if (extras.players.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text(stringResource(R.string.devices_media), style = MaterialTheme.typography.titleSmallEmphasized, modifier = Modifier.weight(1f))
            TextButton(
                enabled = extras.players.any { it.state == "playing" },
                onClick = { link.sendMediaCommand(deviceId, "", "pause") },
            ) { Text(stringResource(R.string.devices_pause_all)) }
        }
        extras.players.forEach { p ->
            Surface(
                shape = KushareShapes.ListCard,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            listOf(p.title, p.artist).filter { it.isNotBlank() }.joinToString(" — ").ifBlank { "—" },
                            style = MaterialTheme.typography.bodyMediumEmphasized,
                            maxLines = 1,
                        )
                    }
                    IconButton(enabled = p.canPrevious, onClick = { link.sendMediaCommand(deviceId, p.id, "previous") }) {
                        Icon(Icons.Rounded.SkipPrevious, contentDescription = stringResource(R.string.media_previous))
                    }
                    FilledIconButton(onClick = { link.sendMediaCommand(deviceId, p.id, "play-pause") }) {
                        Icon(
                            if (p.state == "playing") Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (p.state == "playing") "Pausar" else "Reproducir",
                        )
                    }
                    IconButton(enabled = p.canNext, onClick = { link.sendMediaCommand(deviceId, p.id, "next") }) {
                        Icon(Icons.Rounded.SkipNext, contentDescription = stringResource(R.string.media_next))
                    }
                }
            }
        }
    }
    if (extras.outputs.isNotEmpty()) {
        Text(
            stringResource(R.string.devices_output),
            style = MaterialTheme.typography.titleSmallEmphasized,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        extras.outputs.forEach { o ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                RadioButton(
                    selected = o.active,
                    enabled = extras.outputsSelectable,
                    onClick = { link.sendSetAudioOutput(deviceId, o.id) },
                )
                Text(o.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
        }
    }
}

@Composable
private fun PeerCard(
    peer: MobilePeer,
    extras: PeerExtras?,
    onDirection: (outgoing: Boolean, enabled: Boolean) -> Unit,
    onApps: (outgoing: Boolean) -> Unit,
    onUnpair: () -> Unit,
) {
    val via =
        when {
            peer.transport?.startsWith("bluetooth") == true -> "Bluetooth"
            peer.transport?.startsWith("wifi") == true -> "Wi-Fi"
            else -> peer.transport
        }
    ExpressiveCard(
        modifier = Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        shape = KushareShapes.Hero,
        containerColor = if (peer.connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (peer.connected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShapeIcon(
                icon = if (peer.connected) Icons.Rounded.Computer else Icons.Rounded.LinkOff,
                polygon = if (peer.connected) MaterialShapes.Cookie9Sided else MaterialShapes.Circle,
                container = if (peer.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                content = if (peer.connected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                size = 60.dp,
                active = peer.connected,
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(peer.deviceName, style = MaterialTheme.typography.headlineSmallEmphasized)
                Text(
                    if (peer.connected) stringResource(R.string.devices_connected_via, via ?: "") else stringResource(R.string.devices_disconnected),
                    style = MaterialTheme.typography.bodyMedium,
                )
                extras?.battery?.takeIf { it.present }?.let { b ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (b.charging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryStd,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (b.charging) stringResource(R.string.battery_charging, b.percent.toInt()) else stringResource(R.string.battery_level, b.percent.toInt()),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
        if (peer.connected && extras != null) PeerControls(peer.deviceId, extras)
        Spacer(Modifier.size(8.dp))
        SwitchRow(stringResource(R.string.devices_send_notifications), peer.outgoingEnabled, { onDirection(true, it) })
        SwitchRow(stringResource(R.string.devices_receive_notifications), peer.incomingEnabled, { onDirection(false, it) })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            OutlinedButton(onClick = { onApps(true) }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.devices_apps_out)) }
            OutlinedButton(onClick = { onApps(false) }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.devices_apps_in)) }
        }
        TextButton(onClick = onUnpair) { Text(stringResource(R.string.devices_unpair)) }
    }
}

@Composable
private fun AppFilterDialog(target: AppFilterTarget, onDismiss: () -> Unit) {
    val peers by KushareLink.peers.collectAsState()
    // La versión más reciente del peer (cambia al marcar casillas).
    val peer = peers.firstOrNull { it.deviceId == target.peer.deviceId } ?: target.peer
    val apps: List<MobileApp> = if (target.outgoing) KushareLink.service?.localApps().orEmpty() else peer.peerApps
    val blocked = if (target.outgoing) peer.outgoingBlocked else peer.incomingBlocked

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = KushareShapes.Dialog,
        title = {
            Text(
                if (target.outgoing) stringResource(R.string.devices_apps_out_to, peer.deviceName) else stringResource(R.string.devices_apps_in_from, peer.deviceName),
                style = MaterialTheme.typography.headlineSmallEmphasized,
            )
        },
        text = {
            if (apps.isEmpty()) {
                Text(stringResource(R.string.devices_apps_hint))
            } else {
                Box(modifier = Modifier.heightIn(max = 420.dp)) {
                    LazyColumn {
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
                                Text(app.name, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.common_done)) } },
    )
}
