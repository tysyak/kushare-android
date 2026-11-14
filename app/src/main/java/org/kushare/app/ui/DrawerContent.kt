package org.kushare.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Feedback
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.kushare.app.KushareLink
import org.kushare.app.R
import org.kushare.app.UpdateChecker

/** Destinos del menú lateral. */
enum class DrawerTarget { Devices, Clipboard, ScanQr, Permissions, Settings, Updates, Feedback }

/**
 * Menú lateral al estilo de Enlace Móvil: este teléfono arriba, los PC
 * emparejados con su estado y *Agregar PC*, y debajo las opciones.
 */
@Composable
fun DrawerContent(onSelect: (DrawerTarget) -> Unit) {
    val context = LocalContext.current
    val peers by KushareLink.peers.collectAsState()
    ModalDrawerSheet {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            // Cabecera: este teléfono.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(56.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(KushareLink.deviceName(context), style = MaterialTheme.typography.titleLarge)
                    Text(
                        "kushare ${UpdateChecker.currentVersion(context)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
            Text(
                stringResource(R.string.drawer_linked),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp),
            )
            for (peer in peers) {
                val connected = peer.connected
                val via =
                    when {
                        peer.transport?.startsWith("bluetooth") == true && peer.wifiReady -> "Bluetooth + Wi-Fi"
                        peer.transport?.startsWith("bluetooth") == true -> "Bluetooth"
                        peer.transport != null -> "Wi-Fi"
                        else -> null
                    }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier.fillMaxWidth().clickable { onSelect(DrawerTarget.Devices) }
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier.size(52.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                                .then(
                                    if (connected) Modifier.border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), CircleShape)
                                    else Modifier,
                                ),
                    ) {
                        Icon(Icons.Rounded.Laptop, contentDescription = null)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(peer.deviceName, style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            Icon(
                                if (connected) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel,
                                contentDescription = null,
                                tint = color,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                if (connected) {
                                    via?.let { stringResource(R.string.drawer_connected_via, it) } ?: stringResource(R.string.drawer_connected)
                                } else {
                                    stringResource(R.string.devices_disconnected)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = color,
                            )
                        }
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.fillMaxWidth().clickable { onSelect(DrawerTarget.Devices) }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(16.dp))
                Text(stringResource(R.string.drawer_add_pc), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            DrawerItem(Icons.Rounded.ContentPaste, stringResource(R.string.settings_clipboard)) { onSelect(DrawerTarget.Clipboard) }
            DrawerItem(Icons.Rounded.QrCodeScanner, stringResource(R.string.qr_title)) { onSelect(DrawerTarget.ScanQr) }
            DrawerItem(Icons.Rounded.Security, stringResource(R.string.drawer_permissions)) { onSelect(DrawerTarget.Permissions) }
            DrawerItem(Icons.Rounded.Settings, stringResource(R.string.settings_title)) { onSelect(DrawerTarget.Settings) }
            DrawerItem(Icons.Rounded.SystemUpdate, stringResource(R.string.update_check)) { onSelect(DrawerTarget.Updates) }
            DrawerItem(Icons.Rounded.Feedback, stringResource(R.string.drawer_feedback)) { onSelect(DrawerTarget.Feedback) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DrawerItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    NavigationDrawerItem(
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        selected = false,
        onClick = onClick,
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
    )
}
