package org.kushare.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Botones de las notificaciones de kushare (PIN) y descartes de espejos. */
class LinkActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_PAIR = "org.kushare.app.PAIR_RESPONSE"
        const val ACTION_MIRROR_DISMISSED = "org.kushare.app.MIRROR_DISMISSED"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_KEY = "key"
        const val EXTRA_ACCEPT = "accept"
    }

    override fun onReceive(context: Context, intent: Intent) {
        KushareLink.attach(context)
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return
        when (intent.action) {
            ACTION_PAIR -> KushareLink.respondPairing(deviceId, intent.getBooleanExtra(EXTRA_ACCEPT, false))
            ACTION_MIRROR_DISMISSED -> {
                val key = intent.getStringExtra(EXTRA_KEY) ?: return
                KushareLink.onMirrorDismissed(deviceId, key)
            }
        }
    }
}

/** Arranca el enlace al encender el teléfono (y tras actualizar la app). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> KushareService.start(context)
        }
    }
}
