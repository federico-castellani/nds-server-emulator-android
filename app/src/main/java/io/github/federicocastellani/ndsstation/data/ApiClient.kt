package io.github.federicocastellani.ndsstation.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Client for the NDS Station server. Endpoints and payloads match the web app, the Qt client
 * and the Windows client, including their tolerance for camelCase and snake_case fields.
 */
class ApiClient {
    private val cookies = mutableMapOf<String, Cookie>()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                synchronized(this@ApiClient.cookies) { cookies.forEach { this@ApiClient.cookies[it.name] = it } }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                synchronized(this@ApiClient.cookies) { this@ApiClient.cookies.values.filter { it.matches(url) } }
        })
        .build()

    @Volatile
    private var base: HttpUrl = "http://localhost:3000/".toHttpUrlOrNull()!!

    @Volatile
    var token: String? = null

    var baseUrl: String
        get() = base.toString().trimEnd('/')
        set(value) {
            base = normalizeBaseUrl(value)
        }

    // --- Auth -------------------------------------------------------------------------

    suspend fun login(username: String, password: String): LoginResult = io {
        val body = JSONObject().put("username", username).put("password", password)
        val json = send(Request.Builder().url(url("auth/login")).post(body.toBody()), authenticated = false).use { readObject(it) }

        json?.optString("error")?.takeIf { json.has("error") }?.let { throw ApiException(it) }

        val fromCookie = synchronized(cookies) { cookies["auth_token"]?.value }
        val received = fromCookie?.takeIf { it.isNotEmpty() } ?: json?.optString("token")?.takeIf { it.isNotEmpty() }
            ?: throw ApiException("Autenticazione fallita: nessun token ricevuto.")

        val mustChange = readBool(json, "mustChangePassword", "must_change_password")
        val isAdmin = readBool(json, "isAdmin", "is_admin") || tokenSaysAdmin(received)
        token = received
        LoginResult(received, mustChange, isAdmin)
    }

    suspend fun changePassword(newPassword: String) = io {
        val body = JSONObject().put("new_password", newPassword)
        send(Request.Builder().url(url("auth/change-password")).post(body.toBody())).close()
    }

    fun logout() {
        token = null
        synchronized(cookies) { cookies.clear() }
    }

    // --- Games, ROMs and saves --------------------------------------------------------

    suspend fun games(): List<GameInfo> = io {
        send(Request.Builder().url(url("games")).get()).use { response ->
            readArray(response, "games").objects().map { g ->
                GameInfo(readId(g.opt("id")), g.optString("name").ifEmpty { "Senza titolo" }, g.optString("url").ifEmpty { null })
            }
        }
    }

    /** Streams the ROM into [target]; [progress] gets 0..1 when the size is known. */
    suspend fun downloadRom(gameId: String, target: File, progress: (Float) -> Unit) = io {
        send(Request.Builder().url(url("roms/${enc(gameId)}.nds")).get()).use { response ->
            val body = response.body ?: throw ApiException("Risposta vuota dal server.")
            val total = body.contentLength()
            val part = File(target.path + ".part")
            part.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    var received = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        received += n
                        if (total > 0) progress(received.toFloat() / total)
                    }
                }
            }
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            progress(1f)
        }
    }

    /** The save on the server, or null when the game has none yet. */
    suspend fun downloadSave(gameId: String): ByteArray? = io {
        val request = authorized(Request.Builder().url(url("saves?gameId=${enc(gameId)}")).get()).build()
        execute(request).use { response ->
            if (response.code == 404) return@io null
            ensureSuccess(response)
            // A JSON body means "no save" (e.g. {"error": ...}), never raw save memory.
            if (response.body?.contentType()?.subtype?.contains("json", ignoreCase = true) == true) return@io null
            response.body?.bytes()?.takeIf { it.isNotEmpty() }
        }
    }

    /**
     * Uploads a save "on top of" the version the game started from: [basedOn] is the SHA-256
     * (hex) of that server save, or null when the server had none. If another device saved in
     * the meantime the server refuses with 409 ([ApiException.isConflict]). [force] overwrites
     * regardless, after the player chose to.
     */
    suspend fun uploadSave(gameId: String, data: ByteArray, basedOn: String?, force: Boolean = false) = io {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addPart(field("gameId"), gameId.toRequestBody())
            .addPart(fileField("saveFile", "$gameId.sav"), data.toRequestBody(OCTET))
            .build()
        val request = Request.Builder().url(url("saves")).post(form)
        if (!force) {
            if (basedOn == null) request.header("If-None-Match", "*")
            else request.header("If-Match", "\"$basedOn\"")
        }
        send(request).close()
    }

    // --- Console identity --------------------------------------------------------------
    // The account's DS firmware (Wi-Fi settings and Nintendo WFC user ID), shared by all
    // the user's devices so games see one console instead of offering to erase friends.

    /** The server's copy for this account (null if none); supported = false on old servers. */
    suspend fun getConsole(): Pair<Boolean, ByteArray?> = io {
        execute(authorized(Request.Builder().url(url("console")).get()).build()).use { response ->
            if (response.code == 404 || response.code == 405) return@io false to null
            ensureSuccess(response)
            true to response.body?.bytes()?.takeIf { it.isNotEmpty() }
        }
    }

    suspend fun uploadConsole(firmware: ByteArray) = io {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addPart(fileField("firmware", "console.bin"), firmware.toRequestBody(OCTET))
            .build()
        send(Request.Builder().url(url("console")).post(form)).close()
    }

    // --- Users ------------------------------------------------------------------------

    suspend fun searchUsers(query: String): List<SearchUser> = io {
        send(Request.Builder().url(url("users/search?q=${enc(query)}")).get()).use { response ->
            readArray(response, "users").objects().map { SearchUser(readInt(it.opt("id")), it.optString("username")) }
        }
    }

    suspend fun adminUsers(): List<AdminUser> = io {
        send(Request.Builder().url(url("admin/users")).get()).use { response ->
            readArray(response, "users").objects().map { u ->
                AdminUser(
                    readInt(u.opt("id")),
                    u.optString("username"),
                    truthy(u.opt("is_admin")) || truthy(u.opt("isAdmin")) || truthy(u.opt("role")),
                    truthy(u.opt("must_change_password")) || truthy(u.opt("mustChangePassword")),
                )
            }
        }
    }

    suspend fun registerUser(username: String, password: String) = io {
        val body = JSONObject().put("username", username).put("password", password)
        send(Request.Builder().url(url("auth/register")).post(body.toBody())).close()
    }

    suspend fun deleteUser(id: Int) = io {
        send(Request.Builder().url(url("admin/users/$id")).delete()).close()
    }

    suspend fun resetUserPassword(id: Int, newPassword: String) = io {
        val body = JSONObject().put("new_password", newPassword).put("must_change_password", true)
        send(Request.Builder().url(url("admin/users/$id/reset-password")).post(body.toBody())).close()
    }

    /** Renames a user; they are signed out and sign in again with the new name. */
    suspend fun renameUser(id: Int, username: String) = io {
        val body = JSONObject().put("username", username)
        send(Request.Builder().url(url("admin/users/$id")).patch(body.toBody())).close()
    }

    suspend fun userSaves(id: Int): List<AdminSave> = io {
        send(Request.Builder().url(url("admin/users/$id/saves")).get()).use { response ->
            readArray(response, "saves").objects().map { s ->
                AdminSave(readId(s.opt("gameId")), s.optString("title"), s.optLong("size"), s.optString("updatedAt").ifEmpty { null })
            }
        }
    }

    suspend fun downloadUserSave(id: Int, gameId: String): ByteArray = io {
        send(Request.Builder().url(url("admin/users/$id/saves/${enc(gameId)}")).get()).use { response ->
            response.body?.bytes() ?: throw ApiException("Risposta vuota dal server.")
        }
    }

    /** Replaces (or creates) a player's save; returns the size the server stored. */
    suspend fun replaceUserSave(id: Int, gameId: String, fileName: String, data: ByteArray): Long = io {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addPart(field("gameId"), gameId.toRequestBody())
            .addPart(fileField("saveFile", fileName), data.toRequestBody(OCTET))
            .build()
        send(Request.Builder().url(url("admin/users/$id/saves")).post(form)).use { response ->
            runCatching { JSONObject(response.body?.string() ?: "").optLong("size", data.size.toLong()) }.getOrDefault(data.size.toLong())
        }
    }

    // --- Library management -----------------------------------------------------------

    suspend fun addGameTitle(name: String) = io {
        // The server reads a form field named "title" here.
        val form = MultipartBody.Builder().setType(MultipartBody.FORM).addPart(field("title"), name.toRequestBody()).build()
        send(Request.Builder().url(url("admin/games")).post(form)).close()
    }

    /** Uploads a ROM read from [open] (called once), reporting 0..1 progress. */
    suspend fun uploadGame(name: String, fileName: String, length: Long, open: () -> InputStream, progress: (Float) -> Unit) = io {
        val file = object : RequestBody() {
            override fun contentType() = OCTET
            override fun contentLength() = length

            override fun writeTo(sink: BufferedSink) {
                open().source().use { source ->
                    var sent = 0L
                    while (true) {
                        val n = source.read(sink.buffer, 128 * 1024)
                        if (n < 0) break
                        sent += n
                        sink.emitCompleteSegments()
                        if (length > 0) progress(sent.toFloat() / length)
                    }
                }
            }
        }
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addPart(field("gameName"), name.toRequestBody())
            .addPart(fileField("ndsFile", fileName), file)
            .build()
        send(Request.Builder().url(url("games")).post(form)).close()
    }

    // --- Plumbing ---------------------------------------------------------------------

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun url(path: String): HttpUrl = base.resolve(path) ?: throw ApiException("Indirizzo non valido.")

    private fun authorized(builder: Request.Builder): Request.Builder {
        token?.takeIf { it.isNotEmpty() }?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun execute(request: Request): Response = try {
        http.newCall(request).execute()
    } catch (e: IOException) {
        throw ApiException("Impossibile contattare il server. Controlla la connessione e l'indirizzo.", null, e)
    }

    private fun send(builder: Request.Builder, authenticated: Boolean = true): Response {
        val response = execute((if (authenticated) authorized(builder) else builder).build())
        try {
            ensureSuccess(response)
        } catch (e: ApiException) {
            response.close()
            throw e
        }
        return response
    }

    private fun ensureSuccess(response: Response) {
        if (response.isSuccessful) return
        var message = when (response.code) {
            401 -> "Sessione non valida o scaduta."
            403 -> "Non hai i permessi per questa operazione."
            404 -> "Risorsa non trovata sul server."
            else -> "Errore del server (HTTP ${response.code})."
        }
        try {
            val text = response.peekBody(64 * 1024).string()
            // {"error": "..."} from the original server, {"detail": "..."} from FastAPI.
            val json = JSONObject(text)
            (json.optString("error").ifEmpty { json.opt("detail") as? String ?: "" }).takeIf { it.isNotEmpty() }?.let { message = it }
        } catch (_: JSONException) {
        }
        throw ApiException(message, response.code)
    }

    private fun readObject(response: Response): JSONObject? {
        val text = response.body?.string().orEmpty()
        if (text.isBlank()) return null
        return try {
            // The server answers a failed login with [{"error": ...}, 401].
            if (text.trimStart().startsWith("[")) JSONArray(text).optJSONObject(0) else JSONObject(text)
        } catch (_: JSONException) {
            null
        }
    }

    private fun readArray(response: Response, wrapperKey: String): JSONArray {
        val text = response.body?.string().orEmpty().trim()
        return try {
            when {
                text.startsWith("[") -> JSONArray(text)
                text.startsWith("{") -> JSONObject(text).optJSONArray(wrapperKey) ?: JSONArray()
                else -> JSONArray()
            }
        } catch (_: JSONException) {
            throw ApiException("Risposta del server non valida.")
        }
    }

    private fun JSONObject.toBody() = toString().toRequestBody(JSON)

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()

        fun normalizeBaseUrl(raw: String): HttpUrl {
            var url = raw.trim()
            if (!url.contains("://")) url = "http://$url"
            return url.trimEnd('/').plus("/").toHttpUrlOrNull() ?: throw ApiException("Indirizzo del server non valido.")
        }

        /** Reads admin flags from the JWT payload, as the server may omit them elsewhere. */
        fun tokenSaysAdmin(token: String?): Boolean {
            val json = tokenClaims(token) ?: return false
            return listOf("is_admin", "isAdmin", "role").firstOrNull { json.has(it) }?.let { truthy(json.opt(it)) } ?: false
        }

        /** The account id in the token, which (unlike the username) never changes. */
        fun tokenUserId(token: String?): Int? {
            val json = tokenClaims(token) ?: return null
            return listOf("user_id", "userId", "id").firstOrNull { json.has(it) }
                ?.let { json.opt(it)?.toString()?.toIntOrNull() }
        }

        /** The (unverified) claims of a JWT; the server checks the signature. */
        private fun tokenClaims(token: String?): JSONObject? {
            val parts = token?.split('.') ?: return null
            if (parts.size != 3) return null
            return try {
                JSONObject(String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)))
            } catch (_: Exception) {
                null
            }
        }

        // The Qt client sent quoted multipart names; some server parsers need them.
        private fun field(name: String) = okhttp3.Headers.headersOf("Content-Disposition", "form-data; name=\"$name\"")
        private fun fileField(name: String, fileName: String) =
            okhttp3.Headers.headersOf("Content-Disposition", "form-data; name=\"$name\"; filename=\"${fileName.replace("\"", "")}\"")

        private fun enc(value: String) = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        private fun readBool(json: JSONObject?, vararg keys: String): Boolean {
            json ?: return false
            return keys.firstOrNull { json.has(it) }?.let { truthy(json.opt(it)) } ?: false
        }

        fun truthy(value: Any?): Boolean = when (value) {
            is Boolean -> value
            is Number -> value.toInt() == 1
            is String -> value.lowercase() in setOf("true", "admin", "1")
            else -> false
        }

        private fun readId(value: Any?): String = when (value) {
            is Number -> value.toLong().toString()
            is String -> value
            else -> ""
        }

        private fun readInt(value: Any?): Int = when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: 0
            else -> 0
        }

        private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
    }
}
