package io.github.federicocastellani.ndsstation.emulation

import io.github.federicocastellani.ndsstation.data.ApiClient
import io.github.federicocastellani.ndsstation.data.ApiException
import io.github.federicocastellani.ndsstation.data.LocalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

enum class SyncState { Synced, Pending, Uploading, Failed, Conflict }

/**
 * Keeps the server save in step with the running game (same rules as the Windows client).
 * Every write is backed up locally once writes settle, then uploaded after a quiet period.
 * Uploads only happen when the save changed and differs from what the server already has,
 * and always name the server version they replace: if another device saved in the meantime
 * the server refuses (Conflict) and nothing is overwritten until the player decides.
 */
class SaveSync(private val api: ApiClient, private val store: LocalStore, private val gameId: String, serverSave: ByteArray?) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploadLock = Mutex()
    private var debounce: Job? = null
    private var serverHash: ByteArray? = serverSave?.let(::sha256)

    // Bumped on every change and compared with the generation last uploaded, so a write
    // landing while an upload is in flight is never mistaken for already synced.
    private val generation = AtomicInteger(0)
    @Volatile private var uploadedGeneration = 0

    private val _state = MutableStateFlow(SyncState.Synced)
    val state: StateFlow<SyncState> = _state

    @Volatile var lastError: String? = null
        private set

    /** Flags the save as changed without a write from the game (a loaded state, a local backup). */
    fun markDirty() {
        generation.incrementAndGet()
    }

    /** Call when the core's save counter changes. Games write in many small chunks. */
    fun onSaveWritten() {
        generation.incrementAndGet()
        // A conflict stays visible (and blocks uploads) until the player resolves it on exit;
        // writes are still backed up locally.
        val conflict = _state.value == SyncState.Conflict
        if (!conflict) _state.value = SyncState.Pending
        debounce?.cancel()
        debounce = scope.launch {
            delay(500)
            val save = NativeCore.getSave() ?: return@launch
            val synced = matchesServer(save)
            store.writeSaveBackup(gameId, save, pendingUpload = !synced)
            if (conflict) return@launch
            if (synced) {
                _state.value = SyncState.Synced
                return@launch
            }
            delay(2500)
            flush()
        }
    }

    /**
     * Uploads the current save if the server does not have it yet. Returns false on failure.
     * After a conflict only [force] (the player's choice) uploads.
     */
    suspend fun flush(force: Boolean = false): Boolean = uploadLock.withLock {
        if (_state.value == SyncState.Conflict && !force) return false
        val gen = generation.get()
        // A game that never wrote its save has nothing new for the server.
        val save = if (gen != uploadedGeneration) NativeCore.getSave() else null
        if (save == null || matchesServer(save)) {
            if (save != null) store.markSaveUploaded(gameId)
            _state.value = SyncState.Synced
            return true
        }

        store.writeSaveBackup(gameId, save, pendingUpload = true)
        _state.value = SyncState.Uploading
        try {
            api.uploadSave(gameId, save, serverHash?.toHex(), force)
        } catch (e: ApiException) {
            if (e.isConflict) {
                lastError = "Su un altro dispositivo è stato salvato un progresso più recente di quello da cui è partita questa partita."
                _state.value = SyncState.Conflict
                return false
            }
            lastError = e.message
            _state.value = SyncState.Failed
            return false
        }
        serverHash = sha256(save)
        uploadedGeneration = gen
        store.markSaveUploaded(gameId)
        lastError = null
        _state.value = SyncState.Synced
        true
    }

    fun cancelPending() {
        debounce?.cancel()
    }

    fun close() = scope.cancel()

    private fun matchesServer(save: ByteArray) = serverHash?.contentEquals(sha256(save)) == true

    private companion object {
        fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
