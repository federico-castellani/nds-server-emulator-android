package io.github.federicocastellani.ndsstation

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.federicocastellani.ndsstation.emulation.Gamepad
import io.github.federicocastellani.ndsstation.ui.AppNavigation
import io.github.federicocastellani.ndsstation.ui.theme.NdsTheme

class MainActivity : ComponentActivity() {
    private val app get() = application as NdsApp

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            NdsTheme(app.settings.theme, app.settings.dynamicColor) {
                AppNavigation()
            }
        }
    }

    // Controllers drive the game while one is running; otherwise they navigate the UI.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (app.session.isActive && Gamepad.onKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (app.session.isActive && Gamepad.onMotion(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }
}
