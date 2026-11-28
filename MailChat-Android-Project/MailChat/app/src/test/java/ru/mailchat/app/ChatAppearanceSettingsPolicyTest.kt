package ru.mailchat.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatAppearanceSettingsPolicyTest {

    @Test
    fun defaultsPreserveCurrentChatMetadataAppearance() {
        assertEquals(80, ChatAppearanceSettingsPolicy.DEFAULT_ALPHA_PERCENT)
        assertEquals(10, ChatAppearanceSettingsPolicy.DEFAULT_FONT_SIZE_SP)
    }

    @Test
    fun alphaPercentIsClampedToSupportedRange() {
        assertEquals(0, ChatAppearanceSettingsPolicy.normalizeAlphaPercent(-1))
        assertEquals(0, ChatAppearanceSettingsPolicy.normalizeAlphaPercent(0))
        assertEquals(80, ChatAppearanceSettingsPolicy.normalizeAlphaPercent(80))
        assertEquals(100, ChatAppearanceSettingsPolicy.normalizeAlphaPercent(100))
        assertEquals(100, ChatAppearanceSettingsPolicy.normalizeAlphaPercent(101))
    }

    @Test
    fun fontSizeIsClampedToSupportedRange() {
        assertEquals(8, ChatAppearanceSettingsPolicy.normalizeFontSizeSp(1))
        assertEquals(8, ChatAppearanceSettingsPolicy.normalizeFontSizeSp(8))
        assertEquals(10, ChatAppearanceSettingsPolicy.normalizeFontSizeSp(10))
        assertEquals(16, ChatAppearanceSettingsPolicy.normalizeFontSizeSp(16))
        assertEquals(16, ChatAppearanceSettingsPolicy.normalizeFontSizeSp(24))
    }
}
