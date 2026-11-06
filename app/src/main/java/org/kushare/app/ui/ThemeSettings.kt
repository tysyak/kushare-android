package org.kushare.app.ui

import androidx.annotation.StringRes
import org.kushare.app.R
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Cómo se elige entre claro y oscuro. */
enum class ThemeMode(@StringRes val labelRes: Int) {
    SYSTEM(R.string.theme_system),
    LIGHT(R.string.theme_light),
    DARK(R.string.theme_dark),
    OLED(R.string.theme_oled),
}

data class ThemeConfig(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    /**
     * Material You: colores sacados del fondo de pantalla (Android 12+).
     * Desactivado por defecto: el esquema propio de kushare (violeta) es el
     * mismo que el de la app de escritorio.
     */
    val dynamicColor: Boolean = false,
    /** Con [ThemeMode.SYSTEM] en oscuro, usar negro puro (pantallas OLED). */
    val oledWhenSystemDark: Boolean = true,
)

/** Preferencias de apariencia, persistidas en SharedPreferences. */
object ThemeSettings {
    private const val PREFS = "kushare_ui"
    private val state = MutableStateFlow(ThemeConfig())
    val config: StateFlow<ThemeConfig> = state

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        state.value =
            ThemeConfig(
                mode = runCatching { ThemeMode.valueOf(p.getString("mode", null) ?: "") }.getOrDefault(ThemeMode.SYSTEM),
                dynamicColor = p.getBoolean("dynamic_color_v2", false),
                oledWhenSystemDark = p.getBoolean("oled_system_dark", true),
            )
    }

    fun update(context: Context, transform: (ThemeConfig) -> ThemeConfig) {
        val next = transform(state.value)
        state.value = next
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("mode", next.mode.name)
            .putBoolean("dynamic_color_v2", next.dynamicColor)
            .putBoolean("oled_system_dark", next.oledWhenSystemDark)
            .apply()
    }
}
