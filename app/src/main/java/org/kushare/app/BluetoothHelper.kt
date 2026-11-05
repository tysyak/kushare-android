package org.kushare.app

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.util.UUID
import uniffi.kushare_mobile.ByteStreamIo

private const val TAG = "BluetoothHelper"

/**
 * Debe coincidir con `kushare_transfer::bt::KUSHARE_RFCOMM_UUID` del repo
 * `kushare` (crates/kushare-transfer/src/bt.rs): es el mismo UUID de
 * servicio RFCOMM que usa el cliente Linux, así que ambos lados se
 * encuentran por SDP sin importar quién inicia la conexión.
 */
val KUSHARE_RFCOMM_UUID: UUID = UUID.fromString("b4ac1500-6c92-4e64-8f1e-7e1a0cd4f6a1")

/**
 * Debe coincidir con `kushare_transfer::bt::KUSHARE_LINK_RFCOMM_UUID`: el
 * servicio RFCOMM del enlace persistente (notificaciones), distinto del de
 * transferencias para que ambos puedan escuchar a la vez.
 */
val KUSHARE_LINK_RFCOMM_UUID: UUID = UUID.fromString("6f3a2d10-8c4e-4b7a-9e21-5d0c7b3e9a42")

data class BondedPeer(val name: String, val address: String)

/**
 * Adapta un [BluetoothSocket] ya conectado al `ByteStreamIo` que espera el
 * puente Rust (`send_file_bt`/`receive_over_bt` en kushare_mobile). Los
 * métodos bloquean el hilo que los llama, igual que el `InputStream`/
 * `OutputStream` subyacente: el lado Rust ya corre esto en hilos
 * dedicados, no en una corrutina, precisamente para poder bloquear.
 */
class BluetoothSocketIo(private val socket: BluetoothSocket) : ByteStreamIo {
    private val input = socket.inputStream
    private val output = socket.outputStream

    override fun read(maxLen: UInt): ByteArray {
        val buf = ByteArray(maxLen.toInt())
        val n =
            try {
                input.read(buf)
            } catch (e: Exception) {
                Log.w(TAG, "lectura Bluetooth falló", e)
                -1
            }
        return if (n <= 0) ByteArray(0) else buf.copyOf(n)
    }

    override fun write(data: ByteArray): Boolean =
        try {
            output.write(data)
            output.flush()
            true
        } catch (e: Exception) {
            Log.w(TAG, "escritura Bluetooth falló", e)
            false
        }

    override fun close() {
        runCatching { socket.close() }
    }
}

class BluetoothHelper(private val adapter: BluetoothAdapter) {
    /** Dispositivos ya emparejados (el único universo alcanzable sin
     * permiso de descubrimiento activo por BLE/escaneo clásico; kushare
     * asume emparejamiento previo, igual que el lado Linux). */
    @Suppress("MissingPermission")
    fun bondedPeers(): List<BondedPeer> =
        adapter.bondedDevices.map { BondedPeer(name = it.name ?: it.address, address = it.address) }

    /** Lado cliente: conecta por RFCOMM al dispositivo con dirección MAC
     * `address`, buscando el canal vía SDP (igual que
     * `createRfcommSocketToServiceRecord` siempre ha funcionado en
     * Android). Bloqueante: llamar desde un hilo de fondo. */
    @Suppress("MissingPermission")
    fun connect(address: String): BluetoothSocketIo {
        val device = adapter.getRemoteDevice(address)
        adapter.cancelDiscovery()
        val socket = device.createRfcommSocketToServiceRecord(KUSHARE_RFCOMM_UUID)
        socket.connect()
        return BluetoothSocketIo(socket)
    }

    /** Lado servidor: escucha conexiones RFCOMM entrantes bajo el UUID de
     * kushare. Devuelve el [BluetoothServerSocket] ya escuchando; llamar a
     * [BluetoothServerSocket.accept] (bloqueante) en un bucle desde un
     * hilo de fondo por cada conexión. */
    @Suppress("MissingPermission")
    fun listen(): BluetoothServerSocket =
        adapter.listenUsingRfcommWithServiceRecord("kushare", KUSHARE_RFCOMM_UUID)

    fun isEnabled(): Boolean = adapter.isEnabled

    /** Como [listen], pero para el servicio del enlace persistente. */
    @Suppress("MissingPermission")
    fun listenLink(): BluetoothServerSocket =
        adapter.listenUsingRfcommWithServiceRecord("kushare-link", KUSHARE_LINK_RFCOMM_UUID)

    /** Como [connect], pero al servicio del enlace persistente. Bloqueante. */
    @Suppress("MissingPermission")
    fun connectLink(address: String): BluetoothSocketIo {
        val device = adapter.getRemoteDevice(address)
        adapter.cancelDiscovery()
        val socket = device.createRfcommSocketToServiceRecord(KUSHARE_LINK_RFCOMM_UUID)
        socket.connect()
        return BluetoothSocketIo(socket)
    }
}
