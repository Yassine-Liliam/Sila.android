package app.silati.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Cyan80,
    secondary = CyanGrey80,
    tertiary = Teal80
)

private val LightColorScheme = lightColorScheme(
    primary = Cyan40,
    secondary = CyanGrey40,
    tertiary = Teal40
)

/**
 * The app looks like the phone it runs on through two platform mechanisms, not through any
 * styling of our own:
 *
 * - **Dynamic color** (Android 12+) takes the accent palette from the wallpaper/theme the
 *   owner picked — including on Samsung's One UI, which is why the app follows a Galaxy theme
 *   without knowing anything about Samsung. Below 12 there is no such palette to read, so the
 *   cyan seed below stands in.
 * - **Dark theme** follows the system setting.
 *
 * **Material 3 Expressive is not reachable here.** On the material3 the Compose BOM
 * `2026.02.01` resolves (1.4.0), `MaterialExpressiveTheme`, `MotionScheme` and the rest of
 * that surface are all `internal` — the classes ship in the artifact but nothing outside the
 * library may name them. Getting Expressive means pinning a newer material3 explicitly,
 * against the BOM. Checked 2026-08-09; don't re-try it without changing the version first.
 *
 * No typography argument on purpose: the default M3 scale is the platform's, and the font
 * stays the device's (`FontFamily.Default`) — Roboto on a Pixel, One UI Sans on a Galaxy —
 * which is what makes the text read as native rather than branded.
 */
@Composable
fun SilatiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}