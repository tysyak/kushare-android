package org.kushare.app

import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Botón de kushare en los ajustes rápidos (la cortina con Wi-Fi,
 * Bluetooth, etc.).
 *
 * - Encendido: el enlace se mantiene (escucha y reconexión automática).
 * - Apagado: kushare se detiene del todo.
 * - El subtítulo dice por dónde está conectado, o si está en pausa por
 *   falta de Bluetooth y Wi-Fi.
 * - Encenderlo con el Bluetooth apagado abre el diálogo del sistema para
 *   activarlo (Android 13+ no deja a las apps encenderlo solas).
 */
class KushareTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val turnOn = !KushareSwitch.isEnabled(this)
        KushareSwitch.setEnabled(this, turnOn)
        if (turnOn && !KushareSwitch.isBluetoothOn(this)) requestBluetooth()
        updateTile()
    }

    private fun requestBluetooth() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE),
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val enabled = KushareSwitch.isEnabled(this)
        tile.label = "kushare"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        val subtitle =
            when {
                !enabled -> getString(R.string.home_off)
                !KushareSwitch.radiosAvailable(this) -> getString(R.string.tile_paused)
                else -> connectedSummary() ?: getString(R.string.devices_waiting)
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = subtitle
        tile.contentDescription = "kushare: $subtitle"
        tile.updateTile()
    }

    /** "PC de pruebas · Bluetooth" (o varios separados por coma). */
    private fun connectedSummary(): String? {
        val connected = KushareLink.peers.value.filter { it.connected }
        if (connected.isEmpty()) return null
        return connected.joinToString(", ") { peer ->
            val via =
                when {
                    peer.transport?.startsWith("bluetooth") == true -> "Bluetooth"
                    peer.transport?.startsWith("wifi") == true -> "Wi-Fi"
                    else -> peer.transport ?: "?"
                }
            "${peer.deviceName} · $via"
        }
    }
}
