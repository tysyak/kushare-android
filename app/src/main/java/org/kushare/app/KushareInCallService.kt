package org.kushare.app

import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.InCallService
import android.util.Log

private const val TAG = "KushareInCall"

/** Duración de cada tono y pausa entre teclas (ms). */
private const val TONE_MS = 160L
private const val GAP_MS = 90L

/** Estado de la llamada (`Call.Details.state` es de Android 12). */
private fun Call.stateCompat(): Int =
    if (android.os.Build.VERSION.SDK_INT >= 31) {
        details.state
    } else {
        @Suppress("DEPRECATION")
        state
    }

/**
 * `InCallService` de **app acompañante** (`IN_CALL_SERVICE_COMPANION_APP`):
 * Telecom lo enlaza en cada llamada sin que kushare sea la app de teléfono
 * (necesita el permiso normal `CALL_COMPANION_APP`). Da los objetos
 * [Call], con los que se mandan tonos DTMF a la llamada en curso. No
 * muestra nada: la interfaz sigue siendo la del marcador del teléfono.
 */
class KushareInCallService : InCallService() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "enlazado por Telecom")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: KushareInCallService? = null

        private val main = Handler(Looper.getMainLooper())

        /** La llamada en curso (activa o marcando), si Telecom nos enlazó. */
        private fun activeCall(): Call? {
            val calls = instance?.calls ?: return null
            return calls.firstOrNull { it.stateCompat() == Call.STATE_ACTIVE }
                ?: calls.firstOrNull { it.stateCompat() == Call.STATE_DIALING }
        }

        /**
         * Toca `digits` en la llamada en curso. Responde al PC con el
         * resultado: "no_call" si no hay llamada o Telecom no nos enlazó.
         */
        fun onDtmf(deviceId: String, requestId: ULong, digits: String) {
            main.post {
                val svc = KushareLink.service ?: return@post
                val call = activeCall()
                if (call == null) {
                    svc.sendCommandResult(deviceId, requestId, false, if (instance == null) "no_incall" else "no_call", "")
                    return@post
                }
                val keys = digits.filter { it in "0123456789*#ABCDabcd" }.uppercase()
                var delay = 0L
                for (k in keys) {
                    main.postDelayed({ runCatching { call.playDtmfTone(k) } }, delay)
                    delay += TONE_MS
                    main.postDelayed({ runCatching { call.stopDtmfTone() } }, delay)
                    delay += GAP_MS
                }
                svc.sendCommandResult(deviceId, requestId, true, "", "done")
            }
        }
    }
}
