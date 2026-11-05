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

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true).also {
            enabled.value = it
        }

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
        enabled.value = on
        if (on) KushareService.start(context) else KushareService.stop(context)
        refreshTile(context)
    }

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
