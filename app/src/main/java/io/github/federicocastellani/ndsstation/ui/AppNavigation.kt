package io.github.federicocastellani.ndsstation.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.federicocastellani.ndsstation.NdsApp

object Routes {
    const val LOGIN = "login"
    const val MAIN = "main"
    const val EMULATOR = "emulator"
}

/** Signs out locally and returns to the sign-in screen, optionally with a message. */
fun signOut(nav: NavHostController, message: String? = null) {
    val app = NdsApp.instance
    app.api.logout()
    app.settings.token = null
    app.settings.isAdmin = false
    LoginMessage.pending = message
    nav.navigate(Routes.LOGIN) { popUpTo(0) }
}

/** A message to show on the next sign-in screen (e.g. "session expired"). */
object LoginMessage {
    var pending: String? = null
}

@Composable
fun AppNavigation() {
    val nav = rememberNavController()
    val app = NdsApp.instance

    NavHost(nav, startDestination = Routes.LOGIN) {
        composable(Routes.LOGIN) {
            LoginScreen(onSignedIn = { nav.navigate(Routes.MAIN) { popUpTo(0) } })
        }
        composable(Routes.MAIN) {
            MainScreen(
                onPlay = { game ->
                    app.session.start(game)
                    nav.navigate(Routes.EMULATOR)
                },
                onSignOut = { message -> signOut(nav, message) },
            )
        }
        composable(Routes.EMULATOR) {
            EmulatorScreen(
                onExit = { nav.popBackStack(Routes.MAIN, inclusive = false) },
                onSessionExpired = { signOut(nav, "La sessione è scaduta. Accedi di nuovo.") },
            )
        }
    }
}
