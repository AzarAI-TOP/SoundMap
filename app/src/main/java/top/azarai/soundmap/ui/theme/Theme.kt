package top.azarai.soundmap.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// SoundMap is intentionally always light/white for a clean, calm look.
private val SoundMapColors = lightColorScheme(
    primary = Accent,
    onPrimary = Surface,
    primaryContainer = AccentSoft,
    background = Surface,
    onBackground = OnSurface,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceDim,
    onSurfaceVariant = OnSurfaceMuted,
)

@Composable
fun SoundMapTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = SoundMapColors,
        typography = Typography,
        content = content,
    )
}
