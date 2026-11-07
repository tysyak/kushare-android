package org.kushare.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "KushareUpdate"
private const val REPO = "tysyak/kushare-android"

/**
 * Actualizaciones de la app: busca el último release de kushare-android en
 * GitHub y, si es más nuevo, descarga el APK y lo instala con el
 * instalador del sistema (que comprueba que esté firmado con la misma clave
 * que la app instalada: un APK ajeno no puede sustituirla).
 */
object UpdateChecker {
    data class Release(val version: String, val page: String, val apkUrl: String?)

    sealed interface State {
        data object Idle : State

        data object Checking : State

        data class UpToDate(val version: String) : State

        data class Available(val release: Release) : State

        data object Downloading : State

        data class Failed(val message: String) : State
    }

    val state = MutableStateFlow<State>(State.Idle)

    fun currentVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"

    fun isNewer(latest: String, current: String): Boolean {
        fun parse(v: String) = v.trimStart('v').split('.', '-', '+').take(3).map { it.toIntOrNull() ?: 0 }
        val (a, b) = parse(latest) to parse(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val (x, y) = a.getOrElse(i) { 0 } to b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun get(url: String, ctx: Context): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("User-Agent", "kushare-android/${currentVersion(ctx)}")
            setRequestProperty("Accept", "application/vnd.github+json")
        }

    suspend fun check(ctx: Context): State =
        withContext(Dispatchers.IO) {
            state.value = State.Checking
            val result =
                runCatching {
                    val json = get("https://api.github.com/repos/$REPO/releases/latest", ctx).inputStream.bufferedReader().readText()
                    val obj = JSONObject(json)
                    val assets = obj.optJSONArray("assets")
                    var apk: String? = null
                    for (i in 0 until (assets?.length() ?: 0)) {
                        val a = assets!!.getJSONObject(i)
                        if (a.getString("name").endsWith(".apk")) apk = a.getString("browser_download_url")
                    }
                    Release(obj.getString("tag_name").trimStart('v'), obj.getString("html_url"), apk)
                }
            val next =
                result.fold(
                    onSuccess = { r ->
                        if (isNewer(r.version, currentVersion(ctx))) State.Available(r) else State.UpToDate(currentVersion(ctx))
                    },
                    onFailure = {
                        Log.w(TAG, "no se pudo buscar actualizaciones", it)
                        State.Failed(it.message ?: "error")
                    },
                )
            state.value = next
            next
        }

    /** Descarga el APK y abre el instalador del sistema. */
    suspend fun install(ctx: Context, release: Release) {
        val url = release.apkUrl ?: return
        state.value = State.Downloading
        val result =
            withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(ctx.cacheDir, "update.apk")
                    get(url, ctx).inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                    val installer = ctx.packageManager.packageInstaller
                    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    val id = installer.createSession(params)
                    installer.openSession(id).use { session ->
                        session.openWrite("kushare.apk", 0, file.length()).use { out ->
                            file.inputStream().use { it.copyTo(out) }
                            session.fsync(out)
                        }
                        val status =
                            PendingIntent.getBroadcast(
                                ctx,
                                id,
                                Intent(ctx, UpdateStatusReceiver::class.java),
                                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                            )
                        session.commit(status.intentSender)
                    }
                    file.delete()
                }
            }
        state.value =
            result.fold(onSuccess = { State.Available(release) }, onFailure = { State.Failed(it.message ?: "error") })
    }
}

/** Resultado del instalador: si pide confirmación, abrir su pantalla. */
class UpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "actualizada")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "error"
                UpdateChecker.state.value = UpdateChecker.State.Failed(msg)
            }
        }
    }
}
