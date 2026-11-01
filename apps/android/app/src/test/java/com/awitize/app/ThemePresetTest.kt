package com.awitize.app

import com.awitize.app.ui.AwThemePreset
import com.awitize.app.ui.AwThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The theme presets are pure data — they must round-trip through the prefs
 * id and every palette must map onto a complete Material3 scheme, or the
 * live switch can half-theme a screen.
 */
class ThemePresetTest {

    @Test
    fun `presets have unique ids and round-trip through fromId`() {
        val ids = AwThemePreset.entries.map { it.id }
        assertEquals(AwThemePreset.entries.size, ids.distinct().size)
        for (preset in AwThemePreset.entries) {
            assertEquals(preset, AwThemePreset.fromId(preset.id))
        }
        // unknown and missing ids fall back to the house blue, never null
        assertEquals(AwThemePreset.AWITIZE, AwThemePreset.fromId(null))
        assertEquals(AwThemePreset.AWITIZE, AwThemePreset.fromId("not-a-theme"))
    }

    @Test
    fun `every palette builds a coherent color scheme`() {
        for (preset in AwThemePreset.entries) {
            val p = preset.palette
            val scheme = AwThemes.scheme(p)
            assertEquals(p.brandSoft, scheme.primary)
            assertEquals(p.brand, scheme.inversePrimary)
            assertEquals(p.bg, scheme.background)
            assertEquals(p.text, scheme.onBackground)
            assertEquals(p.muted, scheme.onSurfaceVariant)
            assertEquals(p.danger, scheme.error)
            assertNotNull(preset.typography.headlineMedium)
            assertTrue("gradient needs 2 stops", p.gradient.size >= 2)
            assertTrue("label must be readable", preset.label.isNotBlank())
        }
    }

    @Test
    fun `surface tiers stay ordered darkest to lightest`() {
        fun luminance(c: androidx.compose.ui.graphics.Color) =
            0.2126 * c.red + 0.7152 * c.green + 0.0722 * c.blue
        for (preset in AwThemePreset.entries) {
            val p = preset.palette
            assertTrue(
                "${preset.id}: bg < surface < surfaceHi",
                luminance(p.bg) <= luminance(p.surface) && luminance(p.surface) <= luminance(p.surfaceHi),
            )
        }
    }
}
