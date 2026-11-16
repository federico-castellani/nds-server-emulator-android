package io.github.federicocastellani.ndsstation.emulation

import java.nio.ByteBuffer

/** JNI bindings for the native emulator core (app/src/main/cpp). */
object NativeCore {
    const val SCREEN_WIDTH = 256
    const val SCREEN_HEIGHT = 192
    const val FRAME_BYTES = SCREEN_WIDTH * SCREEN_HEIGHT * 2 * 4

    init {
        System.loadLibrary("ndscore")
    }

    external fun init(): Boolean
    external fun shutdown()

    external fun boot(
        romPath: String,
        save: ByteArray?,
        firmware: ByteArray?,
        saveName: String,
        nickname: String,
        primaryDns: ByteArray,
        secondaryDns: ByteArray,
        mac: ByteArray,
        useJit: Boolean,
    ): Boolean

    external fun stop()
    external fun unload()
    external fun isRunning(): Boolean

    external fun setPaused(paused: Boolean)
    external fun setSpeed(multiplier: Float)
    external fun setVolume(volume: Float)
    external fun setInput(pressedMask: Int)
    external fun setTouch(down: Boolean, x: Int, y: Int)

    /** Copies both screens into [buffer] (a direct buffer of [FRAME_BYTES]); returns the frame id or 0. */
    external fun copyFrame(buffer: ByteBuffer, lastFrame: Long): Long
    external fun fps(): Float

    external fun saveCounter(): Int
    external fun getSave(): ByteArray?
    external fun firmwareCounter(): Int
    external fun getFirmware(): ByteArray?

    external fun saveState(): ByteArray?
    external fun loadState(state: ByteArray): Boolean

    external fun configureNetwork()
    external fun netDescription(): String
    external fun netReport(): String
    external fun lastError(): String
}
