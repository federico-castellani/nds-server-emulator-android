package io.github.federicocastellani.ndsstation.emulation

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/** DS buttons; values match the native core's input mask bits. */
enum class DsKey(val bit: Int) {
    A(1 shl 0), B(1 shl 1), Select(1 shl 2), Start(1 shl 3),
    Right(1 shl 4), Left(1 shl 5), Up(1 shl 6), Down(1 shl 7),
    R(1 shl 8), L(1 shl 9), X(1 shl 10), Y(1 shl 11),
}

/**
 * Physical controllers (Bluetooth/USB gamepads). Buttons map by position, like a real DS:
 * the right face button is A, the bottom one B, the top X and the left Y.
 */
object Gamepad {
    @Volatile var mask = 0
        private set
    @Volatile var fastForward = false
        private set
    @Volatile var connected = false
        private set

    private var buttons = 0
    private var axes = 0

    private fun keyFor(keyCode: Int): DsKey? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_B -> DsKey.A
        KeyEvent.KEYCODE_BUTTON_A -> DsKey.B
        KeyEvent.KEYCODE_BUTTON_Y -> DsKey.X
        KeyEvent.KEYCODE_BUTTON_X -> DsKey.Y
        KeyEvent.KEYCODE_BUTTON_L1 -> DsKey.L
        KeyEvent.KEYCODE_BUTTON_R1 -> DsKey.R
        KeyEvent.KEYCODE_BUTTON_START -> DsKey.Start
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BACK -> DsKey.Select
        KeyEvent.KEYCODE_DPAD_UP -> DsKey.Up
        KeyEvent.KEYCODE_DPAD_DOWN -> DsKey.Down
        KeyEvent.KEYCODE_DPAD_LEFT -> DsKey.Left
        KeyEvent.KEYCODE_DPAD_RIGHT -> DsKey.Right
        else -> null
    }

    private fun isGamepad(source: Int) =
        source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD

    /** Returns true if the event was a controller input and has been consumed. */
    fun onKey(event: KeyEvent): Boolean {
        if (!isGamepad(event.source)) return false
        connected = true
        val down = event.action == KeyEvent.ACTION_DOWN
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
            fastForward = down
            return true
        }
        val key = keyFor(event.keyCode) ?: return false
        buttons = if (down) buttons or key.bit else buttons and key.bit.inv()
        mask = buttons or axes
        return true
    }

    /** Left stick and hat (D-pad reported as axes on many controllers), right trigger. */
    fun onMotion(event: MotionEvent): Boolean {
        if (!isGamepad(event.source) || event.action != MotionEvent.ACTION_MOVE) return false
        connected = true
        val x = event.getAxisValue(MotionEvent.AXIS_X).takeIf { abs(it) > 0.5f } ?: event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val y = event.getAxisValue(MotionEvent.AXIS_Y).takeIf { abs(it) > 0.5f } ?: event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        var a = 0
        if (x < -0.5f) a = a or DsKey.Left.bit
        if (x > 0.5f) a = a or DsKey.Right.bit
        if (y < -0.5f) a = a or DsKey.Up.bit
        if (y > 0.5f) a = a or DsKey.Down.bit
        axes = a
        mask = buttons or axes
        val trigger = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
        fastForward = trigger > 0.5f
        return true
    }

    fun reset() {
        buttons = 0
        axes = 0
        mask = 0
        fastForward = false
    }
}
