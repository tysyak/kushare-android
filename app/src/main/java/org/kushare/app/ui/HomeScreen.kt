package org.kushare.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import org.kushare.app.R
import org.kushare.app.KushareLink
import org.kushare.app.KushareSwitch

/** Estado del enlace resumido para la pantalla de inicio. */
private data class LinkStatus(
    val title: String,
    val detail: String,
    val icon: ImageVector,
    val polygon: RoundedPolygon,
    val active: Boolean,
)

@Composable
fun HomeScreen(
    deviceName: String,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onDevices: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val enabled by KushareSwitch.enabled.collectAsState()
    val paused by KushareSwitch.paused.collectAsState()
    val peers by KushareLink.peers.collectAsState()
    val connected = peers.filter { it.connected }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val status =
        when {
            !enabled -> LinkStatus(stringResource(R.string.home_off), stringResource(R.string.home_off_detail), Icons.Rounded.PowerSettingsNew, MaterialShapes.Circle, false)
            paused -> LinkStatus(stringResource(R.string.home_paused), stringResource(R.string.home_paused_detail), Icons.Rounded.PauseCircle, MaterialShapes.Square, false)
            connected.isNotEmpty() -> {
                val viaBt = connected.any { it.transport?.startsWith("bluetooth") == true }
                LinkStatus(
                    stringResource(R.string.home_connected),
                    connected.joinToString(" · ") { it.deviceName },
                    if (viaBt) Icons.Rounded.Bluetooth else Icons.Rounded.Wifi,
                    MaterialShapes.Cookie9Sided,
                    true,
                )
            }
            peers.isEmpty() -> LinkStatus(stringResource(R.string.home_unpaired), stringResource(R.string.home_unpaired_detail), Icons.Rounded.Devices, MaterialShapes.Pentagon, false)
            else -> LinkStatus(stringResource(R.string.home_waiting), stringResource(R.string.home_waiting_detail), Icons.Rounded.HourglassTop, MaterialShapes.SoftBurst, true)
        }

    val iconEnter = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val iconExit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("kushare", style = MaterialTheme.typography.displaySmallEmphasized) },
                subtitle = { Text(deviceName) },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.settings_title)) }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ExpressiveCard(
                    shape = KushareShapes.Hero,
                    containerColor = if (enabled && !paused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (enabled && !paused) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    onClick = onDevices,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AnimatedContent(
                            targetState = status,
                            transitionSpec = { fadeIn(iconEnter) togetherWith fadeOut(iconExit) },
                            label = "statusIcon",
                        ) { s ->
                            ShapeIcon(
                                icon = s.icon,
                                polygon = s.polygon,
                                container = if (enabled && !paused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                content = if (enabled && !paused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                size = 72.dp,
                                active = s.active,
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(status.title, style = MaterialTheme.typography.headlineLargeEmphasized)
                            Text(status.detail, style = MaterialTheme.typography.bodyMedium)
                        }
                        Switch(checked = enabled, onCheckedChange = { KushareSwitch.setEnabled(context, it) })
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionTile(
                        title = stringResource(R.string.send_title),
                        subtitle = stringResource(R.string.home_send_detail),
                        icon = Icons.Rounded.Send,
                        polygon = MaterialShapes.Clover4Leaf,
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer,
                        accent = MaterialTheme.colorScheme.tertiary,
                        onAccent = MaterialTheme.colorScheme.onTertiary,
                        shape = KushareShapes.ActionLeading,
                        onClick = onSend,
                        modifier = Modifier.weight(1f),
                    )
                    ActionTile(
                        title = stringResource(R.string.receive_title),
                        subtitle = stringResource(R.string.home_receive_detail),
                        icon = Icons.Rounded.Download,
                        polygon = MaterialShapes.Flower,
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        content = MaterialTheme.colorScheme.onSecondaryContainer,
                        accent = MaterialTheme.colorScheme.secondary,
                        onAccent = MaterialTheme.colorScheme.onSecondary,
                        shape = KushareShapes.ActionTrailing,
                        onClick = onReceive,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                ExpressiveCard(shape = KushareShapes.Wide, onClick = onDevices) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ShapeIcon(
                            icon = Icons.Rounded.Devices,
                            polygon = MaterialShapes.Cookie6Sided,
                            container = MaterialTheme.colorScheme.secondary,
                            content = MaterialTheme.colorScheme.onSecondary,
                            size = 52.dp,
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.home_devices_title), style = MaterialTheme.typography.titleLargeEmphasized)
                            Text(
                                when (peers.size) {
                                    0 -> stringResource(R.string.home_devices_none)
                                    1 -> stringResource(R.string.home_devices_one)
                                    else -> stringResource(R.string.home_devices_many, peers.size)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    polygon: RoundedPolygon,
    container: Color,
    content: Color,
    accent: Color,
    onAccent: Color,
    shape: androidx.compose.ui.graphics.Shape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ExpressiveCard(
        modifier = modifier.heightIn(min = 176.dp),
        shape = shape,
        containerColor = container,
        contentColor = content,
        onClick = onClick,
    ) {
        ShapeIcon(icon = icon, polygon = polygon, container = accent, content = onAccent, size = 56.dp)
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineMediumEmphasized)
        Text(subtitle, style = MaterialTheme.typography.bodySmall)
    }
}
