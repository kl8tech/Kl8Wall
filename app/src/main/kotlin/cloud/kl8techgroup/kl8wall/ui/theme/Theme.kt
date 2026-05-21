package cloud.kl8techgroup.kl8wall.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val KL8DarkColorScheme = darkColorScheme(
    primary = Kl8Green,
    onPrimary = Kl8Surface,
    primaryContainer = Kl8GreenDark,
    onPrimaryContainer = Kl8GreenLight,
    secondary = Kl8GreenLight,
    onSecondary = Kl8Surface,
    secondaryContainer = Kl8SurfaceTop,
    onSecondaryContainer = Kl8GreenLight,
    background = Kl8Surface,
    onBackground = Kl8OnSurface,
    surface = Kl8Surface,
    onSurface = Kl8OnSurface,
    surfaceVariant = Kl8SurfaceElevated,
    onSurfaceVariant = Kl8OnSurfaceVariant,
    surfaceContainer = Kl8SurfaceElevated,
    surfaceContainerHigh = Kl8SurfaceTop,
    error = Kl8Error,
    onError = Kl8OnError,
    errorContainer = Kl8ErrorContainer,
    onErrorContainer = Kl8OnSurface,
    outline = Kl8OnSurfaceVariant,
)

/** KL8Wall Material 3 theme — always dark, matching the KL8TechGroup brand. */
@Composable
fun KL8WallTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KL8DarkColorScheme,
        typography = KL8Typography,
        content = content,
    )
}
