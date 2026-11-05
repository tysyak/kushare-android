package org.kushare.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import java.net.Inet4Address
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debe coincidir con `kushare_discovery::mdns::LINK_SERVICE_TYPE`: el
 * servicio del enlace persistente, distinto del de Quick Share. Instancia
 * = device_id; TXT `id` (device_id) y `n` (nombre legible, en claro).
 */
private const val LINK_SERVICE_TYPE = "_kushare-link._tcp."
private const val TAG = "LinkNsd"

class LinkNsd(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null

    fun advertise(deviceId: String, deviceName: String, port: Int) {
        stopAdvertising()
        val info =
            NsdServiceInfo().apply {
                serviceName = deviceId
                serviceType = LINK_SERVICE_TYPE
                setPort(port)
                setAttribute("id", deviceId)
                setAttribute("n", deviceName)
            }
        val listener =
            object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    Log.i(TAG, "enlace anunciado como ${serviceInfo.serviceName} en el puerto $port")
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "registro mDNS del enlace falló: $errorCode")
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            }
        registration = listener
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stopAdvertising() {
        registration?.let { runCatching { nsdManager.unregisterService(it) } }
        registration = null
    }

    /** Escaneo continuo. Los callbacks llegan en hilos de NSD. */
    fun discover(onFound: (LinkSighting) -> Unit, onLost: (String) -> Unit) {
        stopDiscovery()
        // resolveService admite una resolución a la vez: se encolan.
        val queue = ConcurrentLinkedQueue<NsdServiceInfo>()
        val resolving = AtomicBoolean(false)

        fun resolveNext() {
            val next = queue.poll()
            if (next == null) {
                resolving.set(false)
                return
            }
            @Suppress("DEPRECATION")
            nsdManager.resolveService(
                next,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.d(TAG, "no se pudo resolver ${serviceInfo.serviceName}: $errorCode")
                        resolveNext()
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val attrs = serviceInfo.attributes.orEmpty()
                        val id = attrs["id"]?.toString(Charsets.UTF_8) ?: serviceInfo.serviceName
                        val name = attrs["n"]?.toString(Charsets.UTF_8) ?: id
                        // Preferir IPv4: las IPv6 link-local necesitan scope id.
                        val addresses =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                serviceInfo.hostAddresses
                            } else {
                                @Suppress("DEPRECATION")
                                listOfNotNull(serviceInfo.host)
                            }
                        val host = (addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull())?.hostAddress
                        if (host != null) onFound(LinkSighting(id, name, host, serviceInfo.port))
                        resolveNext()
                    }
                },
            )
        }

        val listener =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    queue.add(serviceInfo)
                    if (resolving.compareAndSet(false, true)) resolveNext()
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) = onLost(serviceInfo.serviceName)

                override fun onDiscoveryStopped(serviceType: String) {}

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "no se pudo iniciar el descubrimiento del enlace: $errorCode")
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }
        discovery = listener
        nsdManager.discoverServices(LINK_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stopDiscovery() {
        discovery?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        discovery = null
    }
}
