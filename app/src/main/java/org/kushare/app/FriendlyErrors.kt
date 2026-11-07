package org.kushare.app

import android.content.Context
import android.util.Log
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

private const val TAG = "KushareError"

/**
 * Mensaje para el usuario a partir de un error técnico (excepción de Kotlin,
 * error de Rust o mensaje del sistema). El detalle va solo al log.
 */
fun Context.friendlyError(error: Throwable?): String {
    Log.w(TAG, "error", error)
    val chain = generateSequence(error) { it.cause }.toList()
    return when {
        chain.any { it is SecurityException } -> getString(R.string.error_permission)
        chain.any { it is UnknownHostException } -> getString(R.string.error_no_internet)
        chain.any { it is SocketTimeoutException } -> getString(R.string.error_timeout)
        chain.any { it is ConnectException || it is NoRouteToHostException } -> getString(R.string.error_unreachable)
        else -> friendlyError(chain.joinToString(" | ") { it.message.orEmpty() }, logged = true)
    }
}

/** Como [friendlyError] para un mensaje de texto. */
fun Context.friendlyError(message: String?, logged: Boolean = false): String {
    if (!logged) Log.w(TAG, "error: $message")
    val m = message.orEmpty().lowercase()
    return getString(
        when {
            m.isBlank() -> R.string.error_generic
            "timed out" in m || "timeout" in m || "tiempo" in m || "no respondió" in m -> R.string.error_timeout
            "no está conectado" in m || "not connected" in m || "se cerró" in m || "closed" in m || "broken pipe" in m ->
                R.string.error_not_connected
            "refused" in m || "no route" in m || "unreachable" in m || "rechaz" in m -> R.string.error_unreachable
            "bluetooth" in m && ("off" in m || "disabled" in m || "apagado" in m) -> R.string.error_bluetooth_off
            "permission" in m || "permiso" in m || "denied" in m -> R.string.error_permission
            "unable to resolve host" in m || "network" in m || "internet" in m -> R.string.error_no_internet
            "signature" in m || "firma" in m || "incompatible" in m || "conflict" in m -> R.string.error_update_incompatible
            "socket" in m || "read failed" in m || "connection reset" in m || "io error" in m -> R.string.error_not_connected
            else -> R.string.error_generic
        },
    )
}

/** Para las excepciones de E/S sin más detalle. */
fun Context.friendlyIo(e: IOException): String = friendlyError(e as Throwable)
