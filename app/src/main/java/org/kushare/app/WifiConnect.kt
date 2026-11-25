package org.kushare.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.kushare.app.ui.KushareTheme

private const val CHANNEL = "wifi"
private const val NOTIFICATION_ID = 7304

/**
 * «Compartir internet» del PC: conectarse a la red Wi-Fi que crea el PC, ya
 * sea porque la mandó por el enlace o porque se escaneó su QR.
 */
object WifiConnect {
    /** Red de un QR `WIFI:T:WPA;S:<ssid>;P:<clave>;;`, o `null` si no lo es. */
    fun parseQr(text: String): Pair<String, String>? {
        if (!text.startsWith("WIFI:", ignoreCase = true)) return null
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        for (c in text.substring(5)) {
            when {
                escaped -> current.append(c).also { escaped = false }
                c == '\\' -> escaped = true
                c == ';' -> fields += current.toString().also { current.clear() }
                else -> current.append(c)
            }
        }
        var ssid = ""
        var password = ""
        for (f in fields) {
            val i = f.indexOf(':')
            if (i <= 0) continue
            when (f.substring(0, i).uppercase()) {
                "S" -> ssid = f.substring(i + 1)
                "P" -> password = f.substring(i + 1)
            }
        }
        return if (ssid.isEmpty()) null else ssid to password
    }

    /**
     * El PC mandó su red: preguntar si conectarse (directamente si la app
     * puede abrir ventanas encima; si no, con una notificación).
     */
    fun offer(ctx: Context, ssid: String, password: String) {
        val app = ctx.applicationContext
        val intent =
            Intent(app, WifiConnectActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_SSID, ssid)
                .putExtra(EXTRA_PASSWORD, password)
        if (Settings.canDrawOverlays(app)) {
            runCatching { app.startActivity(intent) }.onSuccess { return }
        }
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, app.getString(R.string.channel_wifi), NotificationManager.IMPORTANCE_HIGH))
        if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val pi = PendingIntent.getActivity(app, 4, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(
            NOTIFICATION_ID,
            Notification
                .Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(app.getString(R.string.wifi_title))
                .setContentText(app.getString(R.string.wifi_text, ssid))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build(),
        )
    }

    /**
     * Añade la red (WPA2) y se conecta. En Android 11+ lo hace el sistema
     * con su propio diálogo de confirmación; en Android 10, como sugerencia
     * de red, y se abre el panel de Wi-Fi.
     *
     * Se pide una MAC aleatoria para esa red: si el teléfono usara la misma
     * MAC que la Wi-Fi del PC (pasa si se clonó la del teléfono en el PC),
     * la tarjeta del PC se confunde y su propia conexión se cae.
     */
    fun connect(activity: ComponentActivity, ssid: String, password: String) {
        val suggestion =
            WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .apply { if (password.isNotEmpty()) setWpa2Passphrase(password) }
                .apply {
                    if (Build.VERSION.SDK_INT >= 33) {
                        setMacRandomizationSetting(WifiNetworkSuggestion.RANDOMIZATION_PERSISTENT)
                    }
                }.build()
        if (Build.VERSION.SDK_INT >= 30) {
            val intent =
                Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
                    .putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(suggestion))
            runCatching { activity.startActivity(intent) }
        } else {
            activity.getSystemService(WifiManager::class.java).addNetworkSuggestions(listOf(suggestion))
            runCatching { activity.startActivity(Intent(Settings.Panel.ACTION_WIFI)) }
        }
    }

    internal const val EXTRA_SSID = "ssid"
    internal const val EXTRA_PASSWORD = "password"
}

/** Diálogo «El PC comparte internet: ¿conectarse a <red>?». */
class WifiConnectActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ssid = intent.getStringExtra(WifiConnect.EXTRA_SSID).orEmpty()
        val password = intent.getStringExtra(WifiConnect.EXTRA_PASSWORD).orEmpty()
        if (ssid.isEmpty()) return finish()
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        setContent {
            KushareTheme {
                Prompt(
                    ssid = ssid,
                    onConnect = {
                        WifiConnect.connect(this, ssid, password)
                        finish()
                    },
                    onCancel = { finish() },
                )
            }
        }
    }

    @Composable
    private fun Prompt(ssid: String, onConnect: () -> Unit, onCancel: () -> Unit) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.wifi_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.wifi_text, ssid), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.wifi_cancel)) }
                Button(onClick = onConnect) { Text(stringResource(R.string.wifi_connect)) }
            }
        }
    }
}
