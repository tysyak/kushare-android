package org.kushare.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Variante de esquema realmente aplicada. */
enum class Appearance { LIGHT, DARK, OLED }

@Composable
fun currentAppearance(config: ThemeConfig): Appearance {
    val systemDark = isSystemInDarkTheme()
    return when (config.mode) {
        ThemeMode.LIGHT -> Appearance.LIGHT
        ThemeMode.DARK -> Appearance.DARK
        ThemeMode.OLED -> Appearance.OLED
        ThemeMode.SYSTEM ->
            when {
                !systemDark -> Appearance.LIGHT
                config.oledWhenSystemDark -> Appearance.OLED
                else -> Appearance.DARK
            }
    }
}

// Paleta propia (sin color dinámico): azul de kushare como primario y un
// magenta de acento como terciario, para que los contenedores destacados
// (estado de conexión, acciones principales) tengan alto contraste.
private val BrandLight =
    lightColorScheme(
        primary = Color(0xFF2E5BD8),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFDCE1FF),
        onPrimaryContainer = Color(0xFF001550),
        secondary = Color(0xFF575E71),
        secondaryContainer = Color(0xFFDBE2F9),
        onSecondaryContainer = Color(0xFF141B2C),
        tertiary = Color(0xFF8B4A8E),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFD6FB),
        onTertiaryContainer = Color(0xFF380B3D),
        background = Color(0xFFFAF8FF),
        surface = Color(0xFFFAF8FF),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF3F3FC),
        surfaceContainer = Color(0xFFEDEDF7),
        surfaceContainerHigh = Color(0xFFE7E7F1),
        surfaceContainerHighest = Color(0xFFE1E2EC),
    )

private val BrandDark =
    darkColorScheme(
        primary = Color(0xFFB6C4FF),
        onPrimary = Color(0xFF002780),
        primaryContainer = Color(0xFF1240B0),
        onPrimaryContainer = Color(0xFFDCE1FF),
        secondary = Color(0xFFBFC6DC),
        secondaryContainer = Color(0xFF3F4759),
        onSecondaryContainer = Color(0xFFDBE2F9),
        tertiary = Color(0xFFFFA9FE),
        onTertiary = Color(0xFF55195B),
        tertiaryContainer = Color(0xFF703374),
        onTertiaryContainer = Color(0xFFFFD6FB),
        background = Color(0xFF121318),
        surface = Color(0xFF121318),
        surfaceContainerLowest = Color(0xFF0D0E13),
        surfaceContainerLow = Color(0xFF1A1B21),
        surfaceContainer = Color(0xFF1E1F25),
        surfaceContainerHigh = Color(0xFF292A2F),
        surfaceContainerHighest = Color(0xFF34343A),
    )

/**
 * Oscuro OLED: fondo y superficie negro puro (píxeles apagados) y los
 * contenedores en una escalera de casi-negros, para que las tarjetas sigan
 * leyéndose sin encender la pantalla entera.
 */
private fun ColorScheme.toOled(): ColorScheme =
    copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceBright = Color(0xFF232327),
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF0A0A0C),
        surfaceContainer = Color(0xFF101013),
        surfaceContainerHigh = Color(0xFF17171B),
        surfaceContainerHighest = Color(0xFF1F1F24),
    )

/**
 * Escala tipográfica expresiva: titulares y displays más pesados y
 * compactos; cuerpos y subtítulos ligeros, para que la jerarquía se lea de
 * un vistazo. Las variantes `*Emphasized` de M3 Expressive se usan en los
 * títulos de pantalla y cifras destacadas (PIN, estado).
 */
private val ExpressiveTypography =
    Typography().run {
        copy(
            displayLarge = displayLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.5).sp),
            displayMedium = displayMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp),
            displaySmall = displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
            headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
            headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold),
            headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
            bodyMedium = bodyMedium.copy(fontWeight = FontWeight.Normal),
            labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
        )
    }

/** Radios grandes en general; los más pequeños para elementos densos. */
private val ExpressiveShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(28.dp),
        largeIncreased = RoundedCornerShape(32.dp),
        extraLarge = RoundedCornerShape(36.dp),
        extraLargeIncreased = RoundedCornerShape(44.dp),
        extraExtraLarge = RoundedCornerShape(56.dp),
    )

@Composable
fun KushareTheme(content: @Composable () -> Unit) {
    val config by ThemeSettings.config.collectAsState()
    val appearance = currentAppearance(config)
    val context = LocalContext.current
    val dynamic = config.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val base =
        when {
            appearance == Appearance.LIGHT && dynamic -> dynamicLightColorScheme(context)
            appearance == Appearance.LIGHT -> BrandLight
            dynamic -> dynamicDarkColorScheme(context)
            else -> BrandDark
        }
    val scheme = if (appearance == Appearance.OLED) base.toOled() else base
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        shapes = ExpressiveShapes,
        typography = ExpressiveTypography,
        content = content,
    )
}
