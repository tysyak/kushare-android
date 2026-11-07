package org.kushare.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Pantalla invisible que manda el portapapeles al PC: Android solo deja
 * leerlo a la app que tiene el foco, así que se abre un instante (desde la
 * notificación de kushare o el botón de ajustes rápidos), lo lee al recibir
 * el foco y se cierra. No es exportada: otra app no puede dispararla.
 */
class ClipboardActivity : Activity() {
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val sent = PhoneData.sendClipboard(this, force = true)
        val message =
            when {
                KushareLink.service == null -> R.string.clipboard_not_connected
                sent -> R.string.clipboard_sent
                else -> R.string.clipboard_empty
            }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }
}

/**
 * "Compartir → Copiar en el PC". Es exportada (cualquier app puede
 * compartir texto), así que pide confirmación: ninguna app puede meter
 * algo en el portapapeles del PC sin que el usuario lo vea y acepte.
 */
class ShareToPcActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (text.isNullOrBlank()) {
            finish()
            return
        }
        val preview = if (text.length > 300) text.take(300) + "…" else text
        AlertDialog.Builder(this)
            .setTitle(R.string.clipboard_share_label)
            .setMessage(preview)
            .setPositiveButton(R.string.common_accept) { _, _ ->
                val sent = PhoneData.sendText(text)
                Toast.makeText(
                    this,
                    if (sent) R.string.clipboard_sent else R.string.clipboard_not_connected,
                    Toast.LENGTH_SHORT,
                ).show()
                finish()
            }.setNegativeButton(R.string.common_reject) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
