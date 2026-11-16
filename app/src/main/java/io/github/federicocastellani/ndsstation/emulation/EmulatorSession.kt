package io.github.federicocastellani.ndsstation.emulation

import io.github.federicocastellani.ndsstation.NdsApp
import io.github.federicocastellani.ndsstation.data.ApiException
import io.github.federicocastellani.ndsstation.data.GameInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.text.DateFormat
import java.util.Date

sealed interface Phase {
    data object Idle : Phase
    data class Loading(val title: String, val message: String, val progress: Float? = null, val cancellable: Boolean = true) : Phase
    data object Running : Phase
    data class Ending(val message: String) : Phase
    /** The game could not start; [sessionExpired] means the user must sign in again. */
    data class Failed(val message: String, val sessionExpired: Boolean = false) : Phase
}

/** A decision only the player can make; the UI shows it as a dialog and calls [answer]. */
class Question(val title: String, val message: String, val options: List<String>, val dismissIndex: Int) {
    internal val result = CompletableDeferred<Int>()
    fun answer(index: Int) {
        result.complete(index)
    }
}

/**
 * One game being played. Lives at application level so screen rotation or recomposition
 * never interrupts it. The save rules are the Windows client's: the server save is never
 * overwritten by an empty one, every write is backed up locally, and leaving the game
 * waits for the server to have the save or for the player to decide.
 */
class EmulatorSession(private val app: NdsApp) {
    private val api get() = app.api
    private val store get() = app.store
    private val settings get() = app.settings

    val phase = MutableStateFlow<Phase>(Phase.Idle)
    val question = MutableStateFlow<Question?>(null)
    val toast = MutableStateFlow<String?>(null)

    private val _syncState = MutableStateFlow(SyncState.Synced)
    val syncState: StateFlow<SyncState> = _syncState

    var game: GameInfo? = null
        private set
    val userPaused = MutableStateFlow(false)
    val fastForwardToggle = MutableStateFlow(false)

    @Volatile var virtualMask = 0
    private var sync: SaveSync? = null
    private var loading: Job? = null
    private var syncWatcher: Job? = null
    private var saveCounter = 0
    private var firmwareCounter = 0
    private var inBackground = false
    private var firmware: ByteArray? = null

    val isActive get() = phase.value !is Phase.Idle

    // --- Starting -----------------------------------------------------------------

    fun start(info: GameInfo) {
        if (isActive) return
        game = info
        userPaused.value = false
        fastForwardToggle.value = false
        virtualMask = 0
        Gamepad.reset()
        phase.value = Phase.Loading("Preparazione del gioco", "Download della ROM…")

        loading = app.scope.launch {
            try {
                val rom = store.romFile(info.id)
                if (!store.isRomCached(info.id)) {
                    api.downloadRom(info.id, rom) { p ->
                        phase.value = Phase.Loading("Preparazione del gioco", "Download della ROM… ${(p * 100).toInt()}%", p)
                    }
                }
                phase.value = Phase.Loading("Preparazione del gioco", "Download del salvataggio…")
                val (serverSave, serverOk) = downloadServerSave(info.id)
                // The console identity shared with the account's other devices.
                firmware = app.identity.resolve()

                val choice = chooseSave(info.id, serverSave, serverOk) ?: run {
                    finishWithoutGame()
                    return@launch
                }

                phase.value = Phase.Loading("Avvio", "Avvio di ${info.name}…", cancellable = false)
                val booted = withContext(Dispatchers.Default) { boot(rom.path, choice.data) }
                if (!booted) {
                    fail("Impossibile avviare il gioco: ${NativeCore.lastError()}")
                    return@launch
                }

                sync = SaveSync(api, store, info.id, if (choice.serverOk) choice.serverSave else null).also { s ->
                    if (choice.fromLocalBackup) {
                        s.markDirty()
                        launch { s.flush() }
                    }
                    syncWatcher = launch { s.state.collect { _syncState.value = it } }
                }
                phase.value = Phase.Running
            } catch (e: CancellationException) {
                finishWithoutGame()
                throw e
            } catch (e: ApiException) {
                if (e.isUnauthorized) phase.value = Phase.Failed("La sessione è scaduta. Accedi di nuovo.", sessionExpired = true)
                else fail(e.message ?: "Errore di rete")
            }
        }
    }

    fun cancelLoading() {
        loading?.cancel()
    }

    private suspend fun downloadServerSave(gameId: String): Pair<ByteArray?, Boolean> = try {
        api.downloadSave(gameId) to true
    } catch (e: ApiException) {
        if (e.isUnauthorized) throw e
        null to false
    }

    /** The save to boot with, and what the server holds (to know what still needs uploading). */
    private class SaveChoice(val data: ByteArray?, val fromLocalBackup: Boolean, val serverSave: ByteArray?, val serverOk: Boolean)

    /**
     * Decides which save to boot with; null when the player backs out. Never starts from an
     * empty save when the server one could not be read: the first upload would overwrite it.
     */
    private suspend fun chooseSave(gameId: String, serverSave: ByteArray?, serverOk: Boolean): SaveChoice? {
        val local = store.readSaveBackup(gameId)
        val localTime = store.saveBackupTime(gameId)?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) }

        if (serverOk && store.hasPendingSave(gameId) && local != null && (serverSave == null || !local.contentEquals(serverSave))) {
            return when (ask(
                "Salvataggio non sincronizzato",
                "Su questo dispositivo c'è un salvataggio del $localTime che non è mai arrivato sul server. Quale vuoi usare?",
                listOf("Usa quello locale", "Usa quello del server", "Annulla"), dismissIndex = 2,
            )) {
                0 -> SaveChoice(local, true, serverSave, true)
                1 -> SaveChoice(serverSave, false, serverSave, true)
                else -> null
            }
        }
        if (serverOk) return SaveChoice(serverSave, false, serverSave, true)

        while (true) {
            val options = if (local != null) listOf("Riprova", "Usa la copia locale", "Annulla") else listOf("Riprova", "Annulla")
            val message = if (local != null)
                "Non è stato possibile scaricare il salvataggio dal server. Puoi usare la copia locale del $localTime."
            else "Non è stato possibile scaricare il salvataggio dal server. Per non sovrascriverlo, il gioco non viene avviato."
            val answer = ask("Salvataggio non disponibile", message, options, dismissIndex = options.lastIndex)
            when {
                answer == 0 -> {
                    phase.value = Phase.Loading("Preparazione del gioco", "Download del salvataggio…")
                    val (retry, ok) = downloadServerSave(gameId)
                    if (ok) return chooseSave(gameId, retry, true)
                }
                local != null && answer == 1 -> return SaveChoice(local, true, null, false)
                else -> return null
            }
        }
    }

    private fun boot(romPath: String, save: ByteArray?): Boolean {
        NativeCore.setVolume(settings.volume)
        NativeCore.setSpeed(1f)
        // An existing console keeps its own MAC address; the account-derived one is only
        // for a brand new console.
        val existing = firmware
        val ok = NativeCore.boot(
            romPath, save, existing, game!!.id, settings.username,
            parseIp(settings.primaryDns), parseIp(settings.secondaryDns),
            if (existing == null) store.consoleMac else ByteArray(6), settings.useJit,
        )
        if (ok) {
            saveCounter = NativeCore.saveCounter()
            firmwareCounter = NativeCore.firmwareCounter()
            // Keep a new console's firmware right away, so MAC and profile stay put.
            if (existing == null) saveFirmware()
        }
        return ok
    }

    private fun parseIp(text: String): ByteArray =
        runCatching { InetAddress.getByName(text.trim()).address.takeIf { it.size == 4 } }.getOrNull() ?: ByteArray(4)

    private fun fail(message: String) {
        runCatching { NativeCore.unload() }
        phase.value = Phase.Failed(message)
    }

    private fun finishWithoutGame() {
        runCatching { NativeCore.unload() }
        phase.value = Phase.Idle
        game = null
    }

    private suspend fun ask(title: String, message: String, options: List<String>, dismissIndex: Int): Int {
        val q = Question(title, message, options, dismissIndex)
        question.value = q
        return try {
            q.result.await()
        } finally {
            question.value = null
        }
    }

    // --- While running -------------------------------------------------------------

    /** Called once per displayed frame by the emulator screen. */
    fun tick() {
        if (phase.value != Phase.Running) return
        NativeCore.setInput(virtualMask or Gamepad.mask)
        val fast = fastForwardToggle.value || Gamepad.fastForward
        NativeCore.setSpeed(if (fast) settings.fastForwardSpeed else 1f)

        val counter = NativeCore.saveCounter()
        if (counter != saveCounter) {
            saveCounter = counter
            sync?.onSaveWritten()
        }
        // The console writes its firmware when it registers with Nintendo WFC (user ID).
        val fw = NativeCore.firmwareCounter()
        if (fw != firmwareCounter) {
            firmwareCounter = fw
            saveFirmware()
        }
    }

    fun setUserPaused(paused: Boolean) {
        userPaused.value = paused
        if (!inBackground) NativeCore.setPaused(paused)
    }

    fun saveState(slot: Int) {
        val g = game ?: return
        val state = NativeCore.saveState()
        if (state == null) {
            toast.value = NativeCore.lastError()
            return
        }
        app.scope.launch(Dispatchers.IO) {
            store.writeState(g.id, slot, state)
            toast.value = "Stato salvato nello slot $slot."
        }
    }

    fun loadState(slot: Int) {
        val g = game ?: return
        app.scope.launch {
            val state = withContext(Dispatchers.IO) { store.readState(g.id, slot) }
            when {
                state == null -> toast.value = "Lo slot $slot è vuoto."
                NativeCore.loadState(state) -> {
                    // The state carries its own copy of the cartridge save.
                    sync?.markDirty()
                    sync?.onSaveWritten()
                    toast.value = "Stato dello slot $slot caricato."
                }
                else -> toast.value = NativeCore.lastError()
            }
        }
    }

    fun stateTime(slot: Int): Long? = game?.let { store.stateTime(it.id, slot) }

    private fun saveFirmware() {
        NativeCore.getFirmware()?.let { app.identity.save(it) }
    }

    // --- Leaving -----------------------------------------------------------------

    /**
     * Pauses the game and makes sure its save reached the server, or the player chose to
     * keep it locally. Returns false if the player went back to the game instead.
     */
    suspend fun end(): Boolean {
        when (phase.value) {
            is Phase.Loading -> {
                cancelLoading()
                return true
            }
            is Phase.Failed -> {
                phase.value = Phase.Idle
                game = null
                return true
            }
            Phase.Idle -> return true
            else -> Unit
        }

        NativeCore.setPaused(true)
        NativeCore.setInput(0)
        NativeCore.setTouch(false, 0, 0)
        val s = sync
        if (s != null) {
            s.cancelPending()
            var force = false
            while (true) {
                phase.value = Phase.Ending("Invio del salvataggio al server…")
                if (s.flush(force)) break
                // Another device saved meanwhile: overwriting would lose that progress,
                // so it is the player's call.
                val conflict = s.state.value == SyncState.Conflict
                val answer = if (conflict) ask(
                    "Salvataggio più recente sul server",
                    "${s.lastError}\n\nPuoi sovrascriverlo con questa partita, oppure uscire senza inviarla: resterà su questo dispositivo e ti verrà proposta al prossimo avvio.",
                    listOf("Sovrascrivi il server", "Esci senza inviare", "Torna al gioco"), dismissIndex = 2,
                ) else ask(
                    "Salvataggio non inviato",
                    "${s.lastError}\n\nSe esci ora il salvataggio resta su questo dispositivo e ti verrà proposto alla prossima partita.",
                    listOf("Riprova", "Esci comunque", "Torna al gioco"), dismissIndex = 2,
                )
                if (answer == 1) break
                if (answer != 0) {
                    phase.value = Phase.Running
                    NativeCore.setPaused(userPaused.value)
                    return false
                }
                force = conflict
            }
        }

        withContext(Dispatchers.IO) {
            saveNetworkLog()
            if (NativeCore.firmwareCounter() != 0) saveFirmware()
            NativeCore.unload()
        }
        syncWatcher?.cancel()
        s?.close()
        sync = null
        _syncState.value = SyncState.Synced
        phase.value = Phase.Idle
        game = null
        return true
    }

    private fun saveNetworkLog() {
        val g = game ?: return
        val report = NativeCore.netReport()
        // Only sessions where the game went online are worth keeping.
        if (report.contains("NAT negotiation") || report.contains("GameSpy") || report.contains("ICMP tipo"))
            runCatching { store.writeNetworkLog(g.name, report) }
    }

    // --- App lifecycle -------------------------------------------------------------

    fun onBackground() {
        inBackground = true
        if (phase.value != Phase.Running) return
        NativeCore.setPaused(true)
        // Android may kill a background app at any time: hand the save over now.
        app.scope.launch {
            sync?.flush()
            withContext(Dispatchers.IO) { saveFirmware() }
        }
    }

    fun onForeground() {
        inBackground = false
        if (phase.value == Phase.Running) NativeCore.setPaused(userPaused.value)
    }
}
