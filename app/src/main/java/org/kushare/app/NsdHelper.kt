package org.kushare.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Base64
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mismo tipo de servicio DNS-SD que usa kushare en Linux
 * (`kushare_discovery::mdns::SERVICE_TYPE`): así ambas plataformas se
 * descubren entre sí por mDNS estándar. El punto final (`n` en el TXT
 * record) es el nombre del dispositivo en base64, igual convención que el
 * lado Linux.
 */
private const val SERVICE_TYPE = "_FC9F5ED42C8A._tcp."
private const val TAG = "NsdHelper"

data class DiscoveredPeer(val name: String, val host: String, val port: Int)

class NsdHelper(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun advertise(endpointId: String, deviceName: String, port: Int) {
        stopAdvertising()
        val info = NsdServiceInfo().apply {
            serviceName = endpointId
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("n", Base64.encodeToString(deviceName.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        }
        val listener =
            object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    Log.i(TAG, "anunciado como ${serviceInfo.serviceName}")
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "registro mDNS falló: $errorCode")
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            }
        registrationListener = listener
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stopAdvertising() {
        registrationListener?.let {
            runCatching { nsdManager.unregisterService(it) }
        }
        registrationListener = null
    }

    /** Escaneo continuo; `onFound` se llama en un hilo de callback de NSD. */
    fun discover(onFound: (DiscoveredPeer) -> Unit) {
        stopDiscovery()
        val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
        val resolving = AtomicBoolean(false)

        fun resolveNext() {
            val next = resolveQueue.poll()
            if (next == null) {
                resolving.set(false)
                return
            }
            nsdManager.resolveService(
                next,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        resolveNext()
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host = serviceInfo.host?.hostAddress
                        val nameBytes = serviceInfo.attributes?.get("n")
                        val name =
                            nameBytes?.let { String(Base64.decode(it, Base64.NO_WRAP), Charsets.UTF_8) }
                                ?: serviceInfo.serviceName
                        if (host != null) {
                            onFound(DiscoveredPeer(name = name, host = host, port = serviceInfo.port))
                        }
                        resolveNext()
                    }
                },
            )
        }

        val listener =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    resolveQueue.add(serviceInfo)
                    if (resolving.compareAndSet(false, true)) resolveNext()
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

                override fun onDiscoveryStopped(serviceType: String) {}

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "no se pudo iniciar el descubrimiento: $errorCode")
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }
        discoveryListener = listener
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            runCatching { nsdManager.stopServiceDiscovery(it) }
        }
        discoveryListener = null
    }
}
