package io.github.federicocastellani.ndsstation.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class ScreenLayout { Auto, Vertical, SideBySide }
enum class AppTheme { System, Light, Dark }

/** Sound while fast-forwarding; the order matches the native core's modes. */
enum class FastForwardSound { NormalPitch, SpedUp, Mute }

/**
 * User preferences, backed by SharedPreferences. Values are Compose state so screens update
 * as soon as they change. The auth token is encrypted with a key kept in the Android Keystore.
 */
class AppSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // No sensible default on a phone: "localhost" would be the phone itself.
    var serverUrl by pref("serverUrl", "")
    var username by pref("username", "")
    var isAdmin by pref("isAdmin", false)

    var theme by enumPref("theme", AppTheme.System)
    var dynamicColor by pref("dynamicColor", true)
    var screenLayout by enumPref("screenLayout", ScreenLayout.Auto)
    var volume by pref("volume", 0.8f)
    var fastForwardSpeed by pref("fastForwardSpeed", 3f)
    var fastForwardSound by enumPref("fastForwardSound", FastForwardSound.NormalPitch)
    var checkForUpdates by pref("checkForUpdates", true)
    var controlsOpacity by pref("controlsOpacity", 0.55f)
    var controlsScale by pref("controlsScale", 1f)
    var haptics by pref("haptics", true)
    var hideControlsWithGamepad by pref("hideControlsWithGamepad", true)
    var useJit by pref("useJit", true)

    var primaryDns by pref("primaryDns", "178.62.43.212")
    var secondaryDns by pref("secondaryDns", "8.8.8.8")

    var token: String?
        get() = prefs.getString("token", null)?.let { TokenCipher.decrypt(it) }
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrEmpty()) remove("token") else putString("token", TokenCipher.encrypt(value))
            }.apply()
        }

    // --- Compose-observable preference delegates --------------------------------------

    private inner class Pref<T>(private val key: String, default: T, read: (T) -> T, private val write: SharedPreferences.Editor.(T) -> Unit) {
        private var state by mutableStateOf(read(default))
        operator fun getValue(owner: Any?, property: kotlin.reflect.KProperty<*>): T = state
        operator fun setValue(owner: Any?, property: kotlin.reflect.KProperty<*>, value: T) {
            state = value
            prefs.edit().apply { write(value) }.apply()
        }
    }

    private fun pref(key: String, default: String) = Pref(key, default, { prefs.getString(key, it) ?: it }) { putString(key, it) }
    private fun pref(key: String, default: Boolean) = Pref(key, default, { prefs.getBoolean(key, it) }) { putBoolean(key, it) }
    private fun pref(key: String, default: Float) = Pref(key, default, { prefs.getFloat(key, it) }) { putFloat(key, it) }

    private inline fun <reified E : Enum<E>> enumPref(key: String, default: E) = Pref(
        key, default,
        { d -> prefs.getString(key, null)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: d },
    ) { putString(key, it.name) }
}

/** AES-GCM with a non-exportable key in the Android Keystore. */
private object TokenCipher {
    private const val ALIAS = "ndsstation-token"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    fun decrypt(stored: String): String? = try {
        val data = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        String(cipher.doFinal(data, 12, data.size - 12))
    } catch (_: Exception) {
        null
    }
}
