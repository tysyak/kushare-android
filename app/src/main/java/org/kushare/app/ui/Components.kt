package org.kushare.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon

/**
 * Formas asimétricas por rol: las tarjetas de acción "apuntan" en
 * diagonal (esquinas grandes en una diagonal, pequeñas en la otra), el
 * héroe de estado es casi una píldora, y las tarjetas de lista son más
 * sobrias. Así cada tipo de contenedor se reconoce por su silueta.
 */
object KushareShapes {
    val ActionLeading = RoundedCornerShape(topStart = 44.dp, topEnd = 16.dp, bottomEnd = 44.dp, bottomStart = 16.dp)
    val ActionTrailing = RoundedCornerShape(topStart = 16.dp, topEnd = 44.dp, bottomEnd = 16.dp, bottomStart = 44.dp)
    val Hero = RoundedCornerShape(topStart = 56.dp, topEnd = 56.dp, bottomEnd = 56.dp, bottomStart = 20.dp)
    val Wide = RoundedCornerShape(topStart = 20.dp, topEnd = 44.dp, bottomEnd = 44.dp, bottomStart = 44.dp)
    val ListCard = RoundedCornerShape(28.dp)
    val Dialog = RoundedCornerShape(topStart = 44.dp, topEnd = 44.dp, bottomEnd = 44.dp, bottomStart = 16.dp)
}

/**
 * Microinteracción de pulsación: el contenedor se encoge un poco con el
 * muelle rápido del esquema de movimiento expresivo y rebota al soltar.
 */
@Composable
fun Modifier.pressScale(interactionSource: MutableInteractionSource, pressed: Float = 0.94f): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressed else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Icono dentro de una forma de [androidx.compose.material3.MaterialShapes]
 * (galleta, trébol, flor…). Con `active`, la forma gira despacio y "late":
 * se usa para señalar actividad (conectado, buscando, transfiriendo).
 */
@Composable
fun ShapeIcon(
    icon: ImageVector,
    polygon: RoundedPolygon,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    active: Boolean = false,
) {
    val transition = rememberInfiniteTransition(label = "shapeIcon")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(12_000, easing = LinearEasing)),
        label = "rotation",
    )
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1_400), RepeatMode.Reverse),
        label = "pulse",
    )
    val shape = polygon.toShape()
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            modifier =
                Modifier
                    .size(size)
                    .graphicsLayer {
                        if (active) {
                            rotationZ = rotation
                            scaleX = pulse
                            scaleY = pulse
                        }
                    }
                    .clip(shape)
                    .background(container),
        )
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(size * 0.45f))
    }
}

/** Tarjeta pulsable con forma propia y realce al pulsar. */
@Composable
fun ExpressiveCard(
    modifier: Modifier = Modifier,
    shape: Shape = KushareShapes.ListCard,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val body: @Composable () -> Unit = { Column(modifier = Modifier.padding(20.dp), content = content) }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.pressScale(interaction),
            shape = shape,
            color = containerColor,
            contentColor = contentColor,
            interactionSource = interaction,
            content = body,
        )
    } else {
        Surface(modifier = modifier, shape = shape, color = containerColor, contentColor = contentColor, content = body)
    }
}

/** Título de sección: corto, enfático y en color de acento. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMediumEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 20.dp, bottom = 8.dp, start = 4.dp),
    )
}

/** Fila con texto principal, explicación opcional y un interruptor con icono. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLargeEmphasized)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            thumbContent = {
                Icon(
                    if (checked) Icons.Rounded.Check else Icons.Rounded.Close,
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize),
                )
            },
        )
    }
}
