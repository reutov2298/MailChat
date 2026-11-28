package ru.mailchat.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatAppearanceSettingsPolicyTest {

    @Test
    fun defaultsPreserveCurrentChatAppearance() {
        assertEquals(80, ChatAppearanceSettingsPolicy.DEFAULT_ALPHA_PERCENT)
        assertEquals(10, ChatAppearanceSettingsPolicy.DEFAULT_FONT_SIZE_SP)
        assertEquals(100, ChatAppearanceSettingsPolicy.DEFAULT_CARD_ALPHA_PERCENT)
        assertEquals(16, ChatAppearanceSettingsPolicy.DEFAULT_MESSAGE_FONT_SIZE_SP)
    }

    @Test
    fun metadataAlphaPercentIsClampedToSupportedRange() {
        assertEquals(0, ChatAppearanceSettingsPolicy.normalizeAlphaPercent(-1))
        assertEquals(0, ChatAppearanceSettingsPolicy.normalizeAlphaPercent(0))
        assertEquals(80, ChatAppearanceSettingsPolicy.normalizeAlphaPercent(80))
        assertEquals(100, ChatAppearanceSettingsPolicy.normalizeAlphaPercent(101))
    }

    @Test
    fun metadataFontSizeIsClampedToSupportedRange() {
        assertEquals(8, ChatAppearanceSettingsPolicy.normalizeFontSizeSp(1))
        assertEquals(8, ChatAppearanceSettingsPolicy.normalizeFontSizeSp(8))
        assertEquals(10, ChatAppearanceSettingsPolicy.normalizeFontSizeSp(10))
        assertEquals(16, ChatAppearanceSettingsPolicy.normalizeFontSizeSp(16))
        assertEquals(16, ChatAppearanceSettingsPolicy.normalizeFontSizeSp(24))
    }

    @Test
    fun cardAlphaPercentIsClampedToSupportedRange() {
        assertEquals(0, ChatAppearanceSettingsPolicy.normalizeCardAlphaPercent(-1))
        assertEquals(0, ChatAppearanceSettingsPolicy.normalizeCardAlphaPercent(0))
        assertEquals(55, ChatAppearanceSettingsPolicy.normalizeCardAlphaPercent(55))
        assertEquals(100, ChatAppearanceSettingsPolicy.normalizeCardAlphaPercent(100))
        assertEquals(100, ChatAppearanceSettingsPolicy.normalizeCardAlphaPercent(101))
    }

    @Test
    fun messageFontSizeIsClampedToSupportedRange() {
        assertEquals(10, ChatAppearanceSettingsPolicy.normalizeMessageFontSizeSp(1))
        assertEquals(10, ChatAppearanceSettingsPolicy.normalizeMessageFontSizeSp(10))
        assertEquals(16, ChatAppearanceSettingsPolicy.normalizeMessageFontSizeSp(16))
        assertEquals(24, ChatAppearanceSettingsPolicy.normalizeMessageFontSizeSp(24))
        assertEquals(24, ChatAppearanceSettingsPolicy.normalizeMessageFontSizeSp(30))
    }
}
