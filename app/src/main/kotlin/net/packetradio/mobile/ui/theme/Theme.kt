package net.packetradio.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Matches the desktop app's icon accent (#1A5FB4) rather than Material's default seed.
private val PgprcBlue = Color(0xFF1A5FB4)
private val PgprcBlueLight = Color(0xFF99C1F1)

private val LightColors = lightColorScheme(
    primary = PgprcBlue,
    secondary = PgprcBlueLight,
)

private val DarkColors = darkColorScheme(
    primary = PgprcBlueLight,
    secondary = PgprcBlue,
)

@Composable
fun PgprcMobileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colorScheme, content = content)
}
