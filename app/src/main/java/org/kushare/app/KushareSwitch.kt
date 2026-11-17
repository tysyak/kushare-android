package org.kushare.app

import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.service.quicksettings.TileService
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Interruptor general de kushare (el botón de ajustes rápidos y el de la
 * pantalla Dispositivos). La elección del usuario se recuerda: apagado,
 * kushare no arranca ni al encender el teléfono ni al abrir la app.
 *
 * Además del interruptor del usuario hay una "pausa" automática: sin
 * Bluetooth ni Wi-Fi no hay por dónde sincronizar, así que el enlace se
 * cierra hasta que vuelva alguno (ver [KushareService]).
 */
object KushareSwitch {
    private const val PREFS = "kushare"
    private const val KEY_ENABLED = "enabled"

    /** Estado visible para la UI: encendido por el usuario. */
    val enabled = MutableStateFlow(true)

    /** Encendido pero en pausa por falta de Bluetooth y Wi-Fi. */
    val paused = MutableStateFlow(false)

    private const val KEY_HYBRID = "link_hybrid"

    /**
     * Modo de conexión: híbrido (por defecto; control por Bluetooth y lo
     * pesado por Wi-Fi si el PC está en la misma red) o solo Bluetooth.
     */
    fun isHybrid(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HYBRID, true)

    /** Activa o desactiva el modo híbrido (Bluetooth para el control y Wi-Fi para lo pesado) y avisa al PC. */
    fun setHybrid(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HYBRID, on).apply()
        KushareService.onLinkModeChanged()
    }

    /** Si el usuario tiene kushare encendido (botón principal o ajuste rápido). */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true).also {
            enabled.value = it
        }

    /** Enciende o apaga kushare: arranca o para [KushareService] y actualiza el ajuste rápido. */
    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
        enabled.value = on
        if (on) KushareService.start(context) else KushareService.stop(context)
        refreshTile(context)
    }

    /** Si el Bluetooth está encendido. */
    fun isBluetoothOn(context: Context): Boolean =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    /**
     * ¿Hay alguna red Wi-Fi conectada? Ante cualquier error (p. ej. falta de
     * permisos) responde `true`: es preferible no pausar a dejar el servicio
     * caído o el enlace en pausa para siempre.
     */
    fun isWifiConnected(context: Context): Boolean =
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
            @Suppress("DEPRECATION")
            cm.allNetworks.any { network ->
                cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        }.getOrDefault(true)

    /** ¿Hay algún medio por el que sincronizar? */
    fun radiosAvailable(context: Context): Boolean =
        runCatching { isBluetoothOn(context) }.getOrDefault(true) || isWifiConnected(context)

    /** Pide al sistema que vuelva a dibujar el botón de ajustes rápidos. */
    fun refreshTile(context: Context) {
        runCatching {
            TileService.requestListeningState(context, ComponentName(context, KushareTileService::class.java))
        }
    }
}
