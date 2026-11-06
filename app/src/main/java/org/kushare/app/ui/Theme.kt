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

// Esquema de colores de kushare (README del orquestador, "Esquema de
// colores"): primario violeta #9d06d8 y acento rosa en el terciario. Los
// roles que la tabla no fija son tonos M3 de la misma paleta. Debe
// coincidir con `src/bin/kushare-gui/style.rs` del escritorio.
private val BrandLight =
    lightColorScheme(
        primary = Color(0xFF9D06D8),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFF5D9FF),
        onPrimaryContainer = Color(0xFF320046),
        secondary = Color(0xFF714D7A),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFF9D8FF),
        onSecondaryContainer = Color(0xFF2A0A33),
        tertiary = Color(0xFF854A63),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFD9E5),
        onTertiaryContainer = Color(0xFF360620),
        background = Color(0xFFFFF7FE),
        onBackground = Color(0xFF1E1A1F),
        surface = Color(0xFFFFF7FE),
        onSurface = Color(0xFF1E1A1F),
        surfaceVariant = Color(0xFFEBDCEB),
        onSurfaceVariant = Color(0xFF4C444F),
        outline = Color(0xFF7E7480),
        error = Color(0xFFBA1A1A),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFBF0FB),
        surfaceContainer = Color(0xFFF5EAF5),
        surfaceContainerHigh = Color(0xFFF0E3F0),
        surfaceContainerHighest = Color(0xFFEBDCEB),
    )

private val BrandDark =
    darkColorScheme(
        primary = Color(0xFFE4B5FF),
        onPrimary = Color(0xFF540078),
        primaryContainer = Color(0xFF7800AA),
        onPrimaryContainer = Color(0xFFF5D9FF),
        secondary = Color(0xFFDFBCE6),
        onSecondary = Color(0xFF402849),
        secondaryContainer = Color(0xFF583E61),
        onSecondaryContainer = Color(0xFFF9D8FF),
        tertiary = Color(0xFFF9B1CD),
        onTertiary = Color(0xFF501D35),
        tertiaryContainer = Color(0xFF6A334B),
        onTertiaryContainer = Color(0xFFFFD9E5),
        background = Color(0xFF151217),
        onBackground = Color(0xFFE8E0E8),
        surface = Color(0xFF151217),
        onSurface = Color(0xFFE8E0E8),
        surfaceVariant = Color(0xFF4A454D),
        onSurfaceVariant = Color(0xFFCDC3CE),
        outline = Color(0xFF968E98),
        error = Color(0xFFFFB4AB),
        surfaceContainerLowest = Color(0xFF100D11),
        surfaceContainerLow = Color(0xFF1E1A1F),
        surfaceContainer = Color(0xFF231F24),
        surfaceContainerHigh = Color(0xFF2D292F),
        surfaceContainerHighest = Color(0xFF38333A),
    )

/**
 * Oscuro OLED (True Black): fondo y superficie negro puro (píxeles
 * apagados), "surface variant" #121013 y los contenedores en una escalera
 * de casi-negros alrededor de él, para que las tarjetas sigan leyéndose sin encender la
 * pantalla entera.
 */
private fun ColorScheme.toOled(): ColorScheme =
    copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceBright = Color(0xFF221F23),
        surfaceVariant = Color(0xFF121013),
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF0A090B),
        surfaceContainer = Color(0xFF0F0D10),
        surfaceContainerHigh = Color(0xFF151316),
        surfaceContainerHighest = Color(0xFF1C191D),
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
