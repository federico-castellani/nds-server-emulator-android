package io.github.federicocastellani.ndsstation

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.federicocastellani.ndsstation.data.ApiClient
import io.github.federicocastellani.ndsstation.data.AppSettings
import io.github.federicocastellani.ndsstation.data.ConsoleIdentity
import io.github.federicocastellani.ndsstation.data.LocalStore
import io.github.federicocastellani.ndsstation.data.Updater
import io.github.federicocastellani.ndsstation.emulation.EmulatorSession
import io.github.federicocastellani.ndsstation.emulation.NativeCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NdsApp : Application() {
    lateinit var settings: AppSettings
        private set
    lateinit var store: LocalStore
        private set
    val api = ApiClient()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var session: EmulatorSession
        private set
    lateinit var identity: ConsoleIdentity
        private set
    lateinit var updater: Updater
        private set

    /** False when the native core failed to load; the library still works. */
    var coreAvailable = false
        private set
    var coreError: String? = null
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = AppSettings(this)
        store = LocalStore(this)
        if (settings.serverUrl.isNotEmpty()) runCatching { api.baseUrl = settings.serverUrl }

        try {
            coreAvailable = NativeCore.init()
        } catch (e: UnsatisfiedLinkError) {
            coreError = "Impossibile caricare il core di emulazione: ${e.message}"
        }

        identity = ConsoleIdentity(api, store)
        updater = Updater(this)
        // Independent of the server and of signing in: an update may be what fixes either.
        scope.launch { updater.checkInBackground(settings.checkForUpdates) }
        session = EmulatorSession(this)
        // A game must hand its save over (and pause) whenever the app leaves the screen:
        // Android may kill a background app without warning.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = session.onBackground()
            override fun onStart(owner: LifecycleOwner) = session.onForeground()
        })
    }

    companion object {
        lateinit var instance: NdsApp
            private set
    }
}
