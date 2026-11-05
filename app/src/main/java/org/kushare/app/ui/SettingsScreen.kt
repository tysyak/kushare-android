package org.kushare.app.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.kushare.app.KushareLink
import org.kushare.app.KushareSwitch

private fun ThemeMode.icon(): ImageVector =
    when (this) {
        ThemeMode.SYSTEM -> Icons.Rounded.BrightnessAuto
        ThemeMode.LIGHT -> Icons.Rounded.LightMode
        ThemeMode.DARK -> Icons.Rounded.DarkMode
        ThemeMode.OLED -> Icons.Rounded.Contrast
    }

private fun ThemeMode.shortLabel(): String = if (this == ThemeMode.OLED) "OLED" else label

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val config by ThemeSettings.config.collectAsState()
    val enabled by KushareSwitch.enabled.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var listenerGranted by remember {
        mutableStateOf(NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    listenerGranted = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Ajustes", style = MaterialTheme.typography.displaySmallEmphasized) },
                subtitle = { Text("Apariencia y conexión") },
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionTitle("Apariencia") }
            item {
                ExpressiveCard(shape = KushareShapes.Wide) {
                    Text("Tema", style = MaterialTheme.typography.titleLargeEmphasized)
                    Text(
                        "OLED usa negro puro: en pantallas OLED los píxeles negros se apagan y ahorran batería.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(16.dp))
                    ThemeModeSelector(selected = config.mode, onSelect = { mode -> ThemeSettings.update(context) { it.copy(mode = mode) } })
                    AnimatedVisibility(
                        visible = config.mode == ThemeMode.SYSTEM,
                        enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(),
                        exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(),
                    ) {
                        SwitchRow(
                            title = "Negro puro cuando el sistema esté en oscuro",
                            subtitle = "Si el teléfono usa el tema oscuro, kushare usará el OLED.",
                            checked = config.oledWhenSystemDark,
                            onCheckedChange = { on -> ThemeSettings.update(context) { it.copy(oledWhenSystemDark = on) } },
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    SwitchRow(
                        title = "Color dinámico",
                        subtitle =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                "Colores sacados de tu fondo de pantalla (Material You)."
                            } else {
                                "Requiere Android 12 o superior."
                            },
                        checked = config.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                        enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                        onCheckedChange = { on -> ThemeSettings.update(context) { it.copy(dynamicColor = on) } },
                    )
                }
            }

            item { SectionTitle("Conexión") }
            item {
                ExpressiveCard(shape = KushareShapes.ListCard) {
                    SwitchRow(
                        title = "kushare activado",
                        subtitle = "También desde el botón de los ajustes rápidos.",
                        checked = enabled,
                        onCheckedChange = { KushareSwitch.setEnabled(context, it) },
                    )
                }
            }
            item {
                ExpressiveCard(
                    shape = KushareShapes.ListCard,
                    containerColor = if (listenerGranted) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.errorContainer,
                    contentColor = if (listenerGranted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ShapeIcon(
                            icon = Icons.Rounded.Notifications,
                            polygon = MaterialShapes.Cookie4Sided,
                            container = if (listenerGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            content = if (listenerGranted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onError,
                            size = 48.dp,
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Acceso a notificaciones", style = MaterialTheme.typography.bodyLargeEmphasized)
                            Text(
                                if (listenerGranted) "Concedido: las notificaciones del teléfono se pueden enviar."
                                else "Sin él no se envían las notificaciones del teléfono.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Spacer(Modifier.size(12.dp))
                    FilledTonalButton(
                        onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(if (listenerGranted) "Revisar" else "Conceder acceso") }
                }
            }

            item { SectionTitle("Acerca de") }
            item {
                ExpressiveCard(shape = KushareShapes.ListCard) {
                    InfoRow("Nombre", KushareLink.deviceName(context))
                    InfoRow("Identificador", KushareLink.service?.deviceId() ?: "—")
                    InfoRow("Versión", context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—")
                }
            }
        }
    }
}

/** Selector de tema: botones conectados (M3 Expressive) que cambian de forma al elegirse. */
@Composable
private fun ThemeModeSelector(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val modes = ThemeMode.entries
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        modes.forEachIndexed { index, mode ->
            val shapes =
                when (index) {
                    0 -> ToggleButtonShapes(
                        ButtonGroupDefaults.connectedLeadingButtonShape,
                        ButtonGroupDefaults.connectedLeadingButtonPressShape,
                        ButtonGroupDefaults.connectedButtonCheckedShape,
                    )
                    modes.lastIndex -> ToggleButtonShapes(
                        ButtonGroupDefaults.connectedTrailingButtonShape,
                        ButtonGroupDefaults.connectedTrailingButtonPressShape,
                        ButtonGroupDefaults.connectedButtonCheckedShape,
                    )
                    else -> ToggleButtonShapes(
                        androidx.compose.material3.ShapeDefaults.Small,
                        ButtonGroupDefaults.connectedMiddleButtonPressShape,
                        ButtonGroupDefaults.connectedButtonCheckedShape,
                    )
                }
            ToggleButton(
                checked = mode == selected,
                onCheckedChange = { onSelect(mode) },
                shapes = shapes,
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(mode.icon(), contentDescription = null)
                    Text(mode.shortLabel(), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMediumEmphasized)
    }
}
