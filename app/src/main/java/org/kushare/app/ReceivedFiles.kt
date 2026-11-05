package org.kushare.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import uniffi.kushare_mobile.ReceiveCallback

private const val TAG = "KushareFiles"
private const val CHANNEL_FILES = "files"

/**
 * Archivos recibidos en segundo plano: el núcleo Rust los escribe en la
 * carpeta privada de la app; al terminar se copian a Descargas (MediaStore,
 * sin permisos de almacenamiento) y se avisa con una notificación que abre
 * el archivo.
 */
class FileReceiveCallback(private val context: Context) : ReceiveCallback {
    override fun onIncomingRequest(deviceName: String, fileName: String, fileSize: ULong) {
        Log.i(TAG, "$deviceName envía \"$fileName\" ($fileSize bytes)")
    }

    override fun onCompleted(deviceName: String, fileName: String, savedTo: String) {
        val uri = publishToDownloads(context, File(savedTo))
        notify(context, "Archivo recibido de $deviceName", fileName, uri)
    }

    override fun onFailed(fileName: String, reason: String) {
        notify(context, "No se pudo recibir \"$fileName\"", reason, null)
    }
}

/** Copia `file` a Descargas y borra la copia privada. Devuelve su URI. */
private fun publishToDownloads(context: Context, file: File): Uri? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return Uri.fromFile(file)
    return runCatching {
        val resolver = context.contentResolver
        val mime =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
                ?: "application/octet-stream"
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore rechazó el archivo")
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        file.delete()
        uri
    }.onFailure { Log.w(TAG, "no se pudo copiar ${file.name} a Descargas", it) }.getOrNull()
}

private fun notify(context: Context, title: String, text: String, uri: Uri?) {
    val nm = context.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(
        NotificationChannel(CHANNEL_FILES, "Archivos recibidos", NotificationManager.IMPORTANCE_DEFAULT),
    )
    val builder =
        Notification.Builder(context, CHANNEL_FILES)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
    if (uri != null) {
        val view =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        builder.setContentIntent(
            PendingIntent.getActivity(context, uri.hashCode(), view, PendingIntent.FLAG_IMMUTABLE),
        )
    }
    runCatching { nm.notify(text.hashCode(), builder.build()) }
}
