package io.github.federicocastellani.ndsstation.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.federicocastellani.ndsstation.data.AppTheme

// Used before Android 12, or with dynamic color turned off.
private val Light = lightColorScheme(
    primary = Color(0xFF3F51B5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDEE0FF),
    onPrimaryContainer = Color(0xFF00105C),
    secondary = Color(0xFF5B5D72),
    secondaryContainer = Color(0xFFE0E0F9),
    tertiary = Color(0xFF77536D),
    tertiaryContainer = Color(0xFFFFD7F1),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFBAC3FF),
    onPrimary = Color(0xFF08218A),
    primaryContainer = Color(0xFF293CA0),
    onPrimaryContainer = Color(0xFFDEE0FF),
    secondary = Color(0xFFC4C5DD),
    secondaryContainer = Color(0xFF434659),
    tertiary = Color(0xFFE6BAD7),
    tertiaryContainer = Color(0xFF5D3C55),
)

@Composable
fun NdsTheme(theme: AppTheme, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val dark = when (theme) {
        AppTheme.Light -> false
        AppTheme.Dark -> true
        AppTheme.System -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = colors, content = content)
}
