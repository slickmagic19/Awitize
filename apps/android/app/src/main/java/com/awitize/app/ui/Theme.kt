package com.awitize.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.awitize.app.Container

/**
 * Every color a screen may paint with. Presets swap the whole object, and the
 * classic Aw* names below are composable getters onto the active palette, so
 * screens theme themselves without knowing a preset system exists.
 */
data class AwPalette(
    val bg: Color,
    val surfaceLowest: Color,
    val surface: Color,
    val surfaceHi: Color,
    val surfaceTop: Color,
    val brand: Color,
    val brandSoft: Color,
    val onBrand: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val text: Color,
    val muted: Color,
    val danger: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val outline: Color,
    val outlineVariant: Color,
    val gradient: List<Color>,
)

/** One installable look. id is what lands in SharedPreferences. */
enum class AwThemePreset(
    val id: String,
    val label: String,
    val tagline: String,
    val palette: AwPalette,
    val typography: Typography,
) {
    AWITIZE(
        id = "awitize",
        label = "Awitize Blue",
        tagline = "Signature blue, deep navy",
        palette = AwPalette(
            bg = Color(0xFF0B1020),
            surfaceLowest = Color(0xFF080D1A),
            surface = Color(0xFF121B33),
            surfaceHi = Color(0xFF1B2544),
            surfaceTop = Color(0xFF202B4E),
            brand = Color(0xFF2E7CF6),
            brandSoft = Color(0xFF58A0FF),
            onBrand = Color(0xFF061127),
            primaryContainer = Color(0xFF183564),
            onPrimaryContainer = Color(0xFFD6E4FF),
            secondary = Color(0xFF8FB8FF),
            onSecondary = Color(0xFF0A1630),
            secondaryContainer = Color(0xFF16294F),
            onSecondaryContainer = Color(0xFFCFE0FF),
            text = Color(0xFFE9EEFA),
            muted = Color(0xFF98A4C4),
            danger = Color(0xFFFF6B6B),
            errorContainer = Color(0xFF4A1620),
            onErrorContainer = Color(0xFFFFD9D9),
            outline = Color(0xFF34436B),
            outlineVariant = Color(0xFF223055),
            gradient = listOf(Color(0xFF63A6FF), Color(0xFF2E7CF6)),
        ),
        typography = awTypography(),
    ),
    SPOTIFY(
        id = "spotify",
        label = "Spotify",
        tagline = "Signal green on carbon black",
        palette = AwPalette(
            bg = Color(0xFF121212),
            surfaceLowest = Color(0xFF050505),
            surface = Color(0xFF1F1F1F),
            surfaceHi = Color(0xFF282828),
            surfaceTop = Color(0xFF2E2E2E),
            brand = Color(0xFF1DB954),
            brandSoft = Color(0xFF1ED760),
            onBrand = Color(0xFF03130A),
            primaryContainer = Color(0xFF103C22),
            onPrimaryContainer = Color(0xFFD2F0DA),
            secondary = Color(0xFF86E0A8),
            onSecondary = Color(0xFF06130A),
            secondaryContainer = Color(0xFF16351F),
            onSecondaryContainer = Color(0xFFCDEED6),
            text = Color(0xFFFFFFFF),
            muted = Color(0xFFA7A7A7),
            danger = Color(0xFFFF4D5E),
            errorContainer = Color(0xFF3D1418),
            onErrorContainer = Color(0xFFFFD9DC),
            outline = Color(0xFF535353),
            outlineVariant = Color(0xFF353535),
            gradient = listOf(Color(0xFF1ED760), Color(0xFF1DB954)),
        ),
        // Circular-equivalent: heavy geometric feel via Roboto Black, tight kerning
        typography = awTypography(
            headlineWeight = FontWeight.Black,
            headlineKern = (-1.0f),
            titleWeight = FontWeight.Bold,
        ),
    ),
    YOUTUBE(
        id = "youtube",
        label = "YouTube",
        tagline = "YouTube Music red on #0F0F0F",
        palette = AwPalette(
            bg = Color(0xFF0F0F0F),
            surfaceLowest = Color(0xFF0A0A0A),
            surface = Color(0xFF1B1B1B),
            surfaceHi = Color(0xFF282828),
            surfaceTop = Color(0xFF383838),
            brand = Color(0xFFFF0033),
            brandSoft = Color(0xFFFF3D63),
            onBrand = Color(0xFF21000A),
            primaryContainer = Color(0xFF3A0A16),
            onPrimaryContainer = Color(0xFFFFD6DE),
            secondary = Color(0xFFFF8FA1),
            onSecondary = Color(0xFF2B0009),
            secondaryContainer = Color(0xFF4A1220),
            onSecondaryContainer = Color(0xFFFFCDD7),
            text = Color(0xFFF1F1F1),
            muted = Color(0xFFAAAAAA),
            danger = Color(0xFFFF6060),
            errorContainer = Color(0xFF4A1620),
            onErrorContainer = Color(0xFFFFD9D9),
            outline = Color(0xFF595959),
            outlineVariant = Color(0xFF383838),
            gradient = listOf(Color(0xFFFF3D63), Color(0xFFFF0033)),
        ),
        // YouTube's actual typeface is Roboto — the Android default. Medium,
        // airy kerning reads exactly like the premium app.
        typography = awTypography(
            headlineWeight = FontWeight.Bold,
            headlineKern = 0.0f,
            titleWeight = FontWeight.Medium,
        ),
    ),
    SOUNDCLOUD(
        id = "soundcloud",
        label = "SoundCloud",
        tagline = "Waveform orange on graphite",
        palette = AwPalette(
            bg = Color(0xFF131317),
            surfaceLowest = Color(0xFF0D0D10),
            surface = Color(0xFF1D1D22),
            surfaceHi = Color(0xFF27272E),
            surfaceTop = Color(0xFF33333B),
            brand = Color(0xFFFF5500),
            brandSoft = Color(0xFFFF7A2E),
            onBrand = Color(0xFF1F0900),
            primaryContainer = Color(0xFF4A1C06),
            onPrimaryContainer = Color(0xFFFFD9C2),
            secondary = Color(0xFFFFA36B),
            onSecondary = Color(0xFF2A1004),
            secondaryContainer = Color(0xFF3D2010),
            onSecondaryContainer = Color(0xFFFFDCC4),
            text = Color(0xFFFFFFFF),
            muted = Color(0xFFA5A5AF),
            danger = Color(0xFFFF4D4D),
            errorContainer = Color(0xFF4A1414),
            onErrorContainer = Color(0xFFFFD9D9),
            outline = Color(0xFF4E4E58),
            outlineVariant = Color(0xFF3A3A42),
            gradient = listOf(Color(0xFFFF8A3D), Color(0xFFFF5500)),
        ),
        // Inter-like neutrality: normal weights, a touch more breathing room
        typography = awTypography(
            headlineWeight = FontWeight.Bold,
            headlineKern = 0.1f,
            titleWeight = FontWeight.SemiBold,
            labelKern = 0.3f,
        ),
    ),
    ;

    companion object {
        fun fromId(id: String?): AwThemePreset =
            entries.firstOrNull { it.id == id } ?: AWITIZE
    }
}

/** Pure palette → Material3 mapping so JVM tests can assert every preset. */
object AwThemes {
    fun scheme(p: AwPalette): ColorScheme = darkColorScheme(
        primary = p.brandSoft,
        onPrimary = p.onBrand,
        primaryContainer = p.primaryContainer,
        onPrimaryContainer = p.onPrimaryContainer,
        inversePrimary = p.brand,
        secondary = p.secondary,
        onSecondary = p.onSecondary,
        secondaryContainer = p.secondaryContainer,
        onSecondaryContainer = p.onSecondaryContainer,
        tertiary = p.brandSoft,
        onTertiary = p.onBrand,
        background = p.bg,
        onBackground = p.text,
        surface = p.bg,
        onSurface = p.text,
        surfaceVariant = p.surfaceHi,
        onSurfaceVariant = p.muted,
        surfaceContainerLowest = p.surfaceLowest,
        surfaceContainerLow = p.bg,
        surfaceContainer = p.surface,
        surfaceContainerHigh = p.surfaceHi,
        surfaceContainerHighest = p.surfaceTop,
        error = p.danger,
        onError = Color(0xFF2A0A0A),
        errorContainer = p.errorContainer,
        onErrorContainer = p.onErrorContainer,
        outline = p.outline,
        outlineVariant = p.outlineVariant,
        scrim = Color(0xCC000000),
    )
}

/**
 * Weight/kerning character per brand. Sizes and line structure stay identical
 * across presets so spacing never shifts when the look changes.
 */
private fun awTypography(
    headlineWeight: FontWeight = FontWeight.Bold,
    headlineKern: Float = -0.5f,
    titleWeight: FontWeight = FontWeight.SemiBold,
    labelKern: Float = 0.0f,
) = Typography(
    headlineMedium = TextStyle(fontWeight = headlineWeight, fontSize = 30.sp, letterSpacing = headlineKern.sp),
    headlineSmall = TextStyle(fontWeight = headlineWeight, fontSize = 26.sp, letterSpacing = (headlineKern * 0.6f).sp),
    titleLarge = TextStyle(fontWeight = titleWeight, fontSize = 21.sp),
    titleMedium = TextStyle(fontWeight = titleWeight, fontSize = 16.sp),
    titleSmall = TextStyle(fontWeight = titleWeight, fontSize = 14.sp),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = labelKern.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = labelKern.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = labelKern.sp),
)

private val LocalPalette = compositionLocalOf { AwThemePreset.AWITIZE.palette }

// Brand tokens as composable views onto the active palette. Screens keep
// reading AwBrand/AwSurface/... exactly as before — theming is free.
val AwBg: Color @Composable get() = LocalPalette.current.bg
val AwSurface: Color @Composable get() = LocalPalette.current.surface
val AwSurfaceHi: Color @Composable get() = LocalPalette.current.surfaceHi
val AwBrand: Color @Composable get() = LocalPalette.current.brand
val AwBrandSoft: Color @Composable get() = LocalPalette.current.brandSoft
val AwText: Color @Composable get() = LocalPalette.current.text
val AwMuted: Color @Composable get() = LocalPalette.current.muted
val AwDanger: Color @Composable get() = LocalPalette.current.danger
val AwGradient: List<Color> @Composable get() = LocalPalette.current.gradient

private val AwShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

@Composable
fun AwitizeTheme(preset: AwThemePreset = AwThemePreset.AWITIZE, content: @Composable () -> Unit) {
    val palette = preset.palette
    val scheme = remember(palette) { AwThemes.scheme(palette) }
    MaterialTheme(
        colorScheme = scheme,
        typography = preset.typography,
        shapes = AwShapes,
    ) {
        // Full-screen overlays (sync, now playing, lyrics) are drawn outside
        // the Scaffold, so they get no content color from it — without this
        // the ambient default is black text on the dark theme.
        CompositionLocalProvider(
            LocalPalette provides palette,
            LocalContentColor provides palette.text,
            content = content,
        )
    }
}

/** Theme chooser — swatch rows, live-switching, persisted through Container. */
@Composable
fun ThemePickerDialog(container: Container, onDismiss: () -> Unit) {
    val current = container.themePreset.value
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AwSurfaceHi,
        title = { Text("Appearance", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AwThemePreset.entries.forEach { preset ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { container.setThemePreset(preset) }
                            .padding(horizontal = 8.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(preset.palette.gradient)),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(preset.label, style = MaterialTheme.typography.bodyLarge, color = AwText)
                            Text(preset.tagline, style = MaterialTheme.typography.bodySmall, color = AwMuted)
                        }
                        if (preset == current) Icon(Icons.Rounded.Check, "Active", tint = AwBrandSoft)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = AwBrandSoft) } },
    )
}
