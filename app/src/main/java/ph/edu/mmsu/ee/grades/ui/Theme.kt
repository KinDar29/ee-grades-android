package ph.edu.mmsu.ee.grades.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/*
 * Colours follow BRAND in Config.gs, so the app, the portal and the generated
 * spreadsheets read as one system: deep green as the primary, gold as the
 * accent. Dynamic colour is deliberately off — a grades portal should look the
 * same on every phone.
 */

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F5132),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3EBDD),
    onPrimaryContainer = Color(0xFF04291A),
    secondary = Color(0xFF7A6514),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF6E7B4),
    onSecondaryContainer = Color(0xFF322802),
    tertiary = Color(0xFF3A5F80),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFBFCFB),
    onBackground = Color(0xFF161C19),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF161C19),
    surfaceVariant = Color(0xFFE6EDE8),
    onSurfaceVariant = Color(0xFF44504A),
    outline = Color(0xFF9AA8A1),
    outlineVariant = Color(0xFFD3DDD7),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF83D5AB),
    onPrimary = Color(0xFF003821),
    primaryContainer = Color(0xFF115138),
    onPrimaryContainer = Color(0xFF9FF2C6),
    secondary = Color(0xFFDAC66E),
    onSecondary = Color(0xFF3A3000),
    secondaryContainer = Color(0xFF544700),
    onSecondaryContainer = Color(0xFFF7E28A),
    tertiary = Color(0xFFA6C8EA),
    onTertiary = Color(0xFF08314F),
    background = Color(0xFF101512),
    onBackground = Color(0xFFE0E4E1),
    surface = Color(0xFF161B18),
    onSurface = Color(0xFFE0E4E1),
    surfaceVariant = Color(0xFF3A463F),
    onSurfaceVariant = Color(0xFFC1CFC6),
    outline = Color(0xFF8B9891),
    outlineVariant = Color(0xFF3A463F),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC)
)

/** Gold, used for the running-grade accent in both themes. */
val AccentGold = Color(0xFFC9A227)

@Composable
fun EeGradesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
