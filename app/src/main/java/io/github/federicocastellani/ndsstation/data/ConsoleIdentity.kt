package io.github.federicocastellani.ndsstation.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

/**
 * Keeps the account's virtual DS identical on every device (same rules as the Windows
 * client). The console firmware holds the Nintendo WFC user ID that games tie their friend
 * roster to; with one per device the game would offer to erase the friends whenever the
 * player switched between PC and phone. The server (/console) holds the reference copy.
 */
class ConsoleIdentity(private val api: ApiClient, private val store: LocalStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploadLock = Mutex()
    @Volatile private var serverHash: ByteArray? = null

    // False when this session could not learn what the server has (offline, old server):
    // then nothing is uploaded, so a console created offline never replaces the real one.
    @Volatile private var canUpload = false

    /** The firmware to boot with: the server's if it has one, else this device's (or null). */
    suspend fun resolve(): ByteArray? {
        val local = store.readFirmware()
        canUpload = false
        return try {
            val (supported, server) = api.getConsole()
            if (!supported) return local
            canUpload = true
            if (server != null) {
                serverHash = sha256(server)
                if (local == null || !sha256(local).contentEquals(serverHash)) runCatching { store.writeFirmware(server) }
                server
            } else {
                // The server has no console for this account yet: this device's becomes it.
                serverHash = null
                local?.let { upload(it) }
                local
            }
        } catch (e: ApiException) {
            if (e.isUnauthorized) throw e
            local
        }
    }

    /** Stores the firmware the console just wrote, here and on the server. */
    fun save(firmware: ByteArray) {
        runCatching { store.writeFirmware(firmware) }
        if (canUpload) upload(firmware)
    }

    private fun upload(firmware: ByteArray) {
        scope.launch {
            uploadLock.withLock {
                val hash = sha256(firmware)
                if (serverHash?.contentEquals(hash) == true) return@withLock
                try {
                    api.uploadConsole(firmware)
                    serverHash = hash
                } catch (_: ApiException) {
                    // Kept locally; the next session uploads it if the server still has none.
                }
            }
        }
    }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
}
