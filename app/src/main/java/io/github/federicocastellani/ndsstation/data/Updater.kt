package io.github.federicocastellani.ndsstation.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.github.federicocastellani.ndsstation.NdsApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A newer release published on GitHub. */
data class UpdateInfo(
    val version: String,
    val notes: String,
    val pageUrl: String,
    val downloadUrl: String,
    val size: Long,
    val sha256: String?,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    /** Handed to Android's installer, which asks the player to confirm. */
    data object Installing : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * In-app updates from the GitHub releases of this repository. The APK is downloaded,
 * checked against the size and SHA-256 GitHub reports, and handed to Android's package
 * installer; Android asks the player to confirm and keeps the app's data. Updates only
 * install over an APK signed with the same key, which the release builds keep.
 */
class Updater(private val context: Context) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _available = MutableStateFlow<UpdateInfo?>(null)
    val available: StateFlow<UpdateInfo?> = _available

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    val currentVersion: String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    private val backgroundChecked = AtomicBoolean(false)

    /** Once per app run, when enabled in the settings; failures are ignored. */
    suspend fun checkInBackground(enabled: Boolean) {
        if (!enabled || backgroundChecked.getAndSet(true)) return
        runCatching { check() }
    }

    /** Returns the newer release, or null when this is the latest version. Throws IOException. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPOSITORY/releases/latest")
            // GitHub requires a User-Agent; a generic one, nothing about the user.
            .header("User-Agent", "NDSStation-Updater/$currentVersion")
            .header("Accept", "application/vnd.github+json")
            .build()
        val release = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub ha risposto ${response.code}.")
            JSONObject(response.body?.string() ?: throw IOException("Risposta vuota da GitHub."))
        }

        val version = release.optString("tag_name").trimStart('v', 'V')
        if (compareVersions(version, currentVersion) <= 0) {
            _available.value = null
            return@withContext null
        }
        val assets = release.optJSONArray("assets") ?: return@withContext null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            if (!name.startsWith(ASSET_PREFIX) || !name.endsWith(".apk")) continue
            val digest = asset.optString("digest")
            val info = UpdateInfo(
                version = version,
                notes = release.optString("body"),
                pageUrl = release.optString("html_url", "https://github.com/$REPOSITORY/releases"),
                downloadUrl = asset.getString("browser_download_url"),
                size = asset.optLong("size"),
                sha256 = digest.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
            )
            _available.value = info
            return@withContext info
        }
        null   // a release without the APK yet (still uploading)
    }

    /** Android lets an app install packages only after the player allowed it once. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Downloads, verifies and installs; progress and errors are reported through [state]. */
    suspend fun downloadAndInstall(info: UpdateInfo) {
        try {
            val apk = download(info)
            _state.value = UpdateState.Installing
            withContext(Dispatchers.IO) { install(apk) }
        } catch (e: IOException) {
            _state.value = UpdateState.Failed(e.message ?: "Download non riuscito.")
        } catch (e: SecurityException) {
            _state.value = UpdateState.Failed("Android non permette a NDS Station di installare aggiornamenti.")
        }
    }

    private suspend fun download(info: UpdateInfo): File = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val target = File(folder, "$ASSET_PREFIX${info.version}.apk")
        _state.value = UpdateState.Downloading(0f)

        val request = Request.Builder().url(info.downloadUrl).header("User-Agent", "NDSStation-Updater/$currentVersion").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download non riuscito (${response.code}).")
            val body = response.body ?: throw IOException("Download non riuscito.")
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        if (info.size > 0) _state.value = UpdateState.Downloading(done.toFloat() / info.size)
                    }
                }
            }
            if (done != info.size) throw IOException("Il file scaricato è incompleto. Riprova.")
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (info.sha256 != null && !hex.equals(info.sha256, ignoreCase = true))
                throw IOException("Il file scaricato non corrisponde a quello pubblicato. Riprova.")
        }
        target
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Skips the confirmation when Android allows it (the app installed itself before).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val callback = PendingIntent.getBroadcast(
                context, sessionId, Intent(context, InstallResultReceiver::class.java),
                // Mutable: the installer adds the result and the confirmation screen to it.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
    }

    internal fun onInstallResult(status: Int, message: String?) {
        _state.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> UpdateState.Idle   // the process is replaced anyway
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateState.Idle   // the player said no
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                UpdateState.Failed("Questo aggiornamento è firmato in modo diverso dall'app installata: disinstalla NDS Station e installa l'APK dalla pagina della release.")
            PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateState.Failed("Spazio insufficiente per installare l'aggiornamento.")
            else -> UpdateState.Failed("Installazione non riuscita${message?.let { ": $it" } ?: "."}")
        }
    }

    fun dismissError() {
        if (_state.value is UpdateState.Failed) _state.value = UpdateState.Idle
    }

    companion object {
        const val REPOSITORY = "federico-castellani/nds-server-emulator-android"
        private const val ASSET_PREFIX = "NDSStation-"

        /** Compares dotted versions numerically ("2.10.0" > "2.9.1"). */
        fun compareVersions(a: String, b: String): Int {
            val x = a.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            val y = b.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }
    }
}

/** Receives the package installer's progress: shows its confirmation screen, reports errors. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
            return
        }
        NdsApp.instance.updater.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
