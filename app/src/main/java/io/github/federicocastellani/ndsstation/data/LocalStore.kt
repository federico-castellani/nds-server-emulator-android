package io.github.federicocastellani.ndsstation.data

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Files kept on the device: the ROM cache, a backup of every save, save states, the console
 * firmware and network logs. Everything is scoped by server and user, like the Windows client.
 */
class LocalStore(context: Context) {
    private val root = File(context.filesDir, "ndsstation")
    private val cacheRoot = File(context.cacheDir, "roms")

    private var scope = "default"
    private var scopeHash = ByteArray(32)

    /**
     * Selects the account whose files are used: per server and account id, so renaming the
     * account changes nothing here. Versions before 2.2 kept them per username: the folders of
     * the name just used to sign in (so of this same account) are moved over once. Without an
     * id (a server that does not send one) the username is still used.
     */
    fun setScope(serverUrl: String, userId: Int?, username: String) {
        val server = serverUrl.trim().trimEnd('/').lowercase()
        val legacy = scopeOf("$server|${username.trim().lowercase()}")
        if (userId == null) {
            scopeHash = legacy.first; scope = legacy.second
            return
        }
        // A newline cannot be part of a username, so this never matches a username scope.
        val current = scopeOf("$server\nid=$userId")
        scopeHash = current.first; scope = current.second
        moveScope(legacy.second, scope)
    }

    private fun scopeOf(raw: String): Pair<ByteArray, String> {
        val hash = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return hash to hash.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun moveScope(from: String, to: String) {
        // Left where it is if it cannot move: not lost, only not visible to this account.
        for (area in listOf(File(root, "saves"), File(root, "states"), cacheRoot)) {
            val source = File(area, from)
            val target = File(area, to)
            if (source.isDirectory && (!target.exists() || target.list().isNullOrEmpty())) {
                target.delete()
                source.renameTo(target)
            }
        }
        val firmware = File(root, "firmware/$from.bin")
        val newFirmware = File(root, "firmware/$to.bin")
        if (firmware.exists() && !newFirmware.exists()) firmware.renameTo(newFirmware)
    }

    private fun dir(name: String) = File(root, "$name/$scope").apply { mkdirs() }
    private fun safe(id: String) = id.map { if (it.isLetterOrDigit() || it in "-_.") it else '_' }.joinToString("")

    // --- ROM cache --------------------------------------------------------------------

    fun romFile(gameId: String) = File(File(cacheRoot, scope).apply { mkdirs() }, safe(gameId) + ".nds")
    fun isRomCached(gameId: String) = romFile(gameId).let { it.exists() && it.length() > 0 }
    fun deleteCachedRom(gameId: String) = romFile(gameId).delete()
    fun cacheSize(): Long = cacheRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun clearCache() = cacheRoot.deleteRecursively()

    // --- Save backups -----------------------------------------------------------------
    // Every save the game writes lands here first. A ".pending" marker means the file has
    // not reached the server yet, so it is offered again on the next launch.

    private fun saveFile(gameId: String) = File(dir("saves"), safe(gameId) + ".sav")
    private fun pendingFile(gameId: String) = File(saveFile(gameId).path + ".pending")

    fun writeSaveBackup(gameId: String, data: ByteArray, pendingUpload: Boolean) {
        atomicWrite(saveFile(gameId), data)
        if (pendingUpload) pendingFile(gameId).writeText(System.currentTimeMillis().toString()) else markSaveUploaded(gameId)
    }

    fun markSaveUploaded(gameId: String) {
        pendingFile(gameId).delete()
    }

    fun hasPendingSave(gameId: String) = pendingFile(gameId).exists() && saveFile(gameId).exists()
    fun saveBackupTime(gameId: String): Long? = saveFile(gameId).takeIf { it.exists() }?.lastModified()
    fun readSaveBackup(gameId: String): ByteArray? = try {
        saveFile(gameId).takeIf { it.exists() }?.readBytes()
    } catch (_: IOException) {
        null
    }

    // --- Save states (gzip: ~19 MB of mostly empty memory each) ------------------------

    private fun stateFile(gameId: String, slot: Int) = File(dir("states"), "${safe(gameId)}.slot$slot.mst.gz")

    fun writeState(gameId: String, slot: Int, data: ByteArray) {
        val target = stateFile(gameId, slot)
        val temp = File(target.path + ".tmp")
        GZIPOutputStream(temp.outputStream()).use { it.write(data) }
        temp.renameTo(target)
    }

    fun readState(gameId: String, slot: Int): ByteArray? =
        stateFile(gameId, slot).takeIf { it.exists() }?.let { f -> GZIPInputStream(f.inputStream()).use { it.readBytes() } }

    fun stateTime(gameId: String, slot: Int): Long? = stateFile(gameId, slot).takeIf { it.exists() }?.lastModified()

    // --- Console identity -------------------------------------------------------------
    // Each account is its own virtual DS: a stable MAC address (Nintendo's 00:09:BF prefix)
    // and a firmware image that keeps the Nintendo WFC user ID between sessions. Without
    // them every connection looks like a new console and games wipe their friend roster.

    val consoleMac: ByteArray get() = byteArrayOf(0x00, 0x09, 0xBF.toByte(), scopeHash[0], scopeHash[1], scopeHash[2])

    private val firmwareFile get() = File(File(root, "firmware").apply { mkdirs() }, "$scope.bin")
    fun readFirmware(): ByteArray? = firmwareFile.takeIf { it.exists() }?.readBytes()
    fun writeFirmware(data: ByteArray) = atomicWrite(firmwareFile, data)

    // --- Network logs -----------------------------------------------------------------

    val logDir: File get() = File(root, "logs").apply { mkdirs() }

    fun writeNetworkLog(gameName: String, report: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val file = File(logDir, "rete-$stamp-${safe(gameName)}.txt")
        file.writeText("$gameName - ${Date()}\n\n$report")
        logDir.listFiles { f -> f.name.startsWith("rete-") }?.sortedByDescending { it.name }?.drop(30)?.forEach { it.delete() }
        return file
    }

    fun latestNetworkLog(): File? = logDir.listFiles { f -> f.name.startsWith("rete-") }?.maxByOrNull { it.name }

    private fun atomicWrite(target: File, data: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.path + ".tmp")
        temp.writeBytes(data)
        if (!temp.renameTo(target)) {
            target.writeBytes(data)
            temp.delete()
        }
    }
}
