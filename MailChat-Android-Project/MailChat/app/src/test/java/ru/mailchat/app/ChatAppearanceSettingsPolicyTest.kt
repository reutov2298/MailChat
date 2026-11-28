package ru.mailchat.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class ChatAppearanceSettingsPolicyTest {
    @Test fun mainSettingsSectionsFollowApprovedOrder() {
        assertEquals(
            listOf(
                MainSettingsSection.GOOGLE_ACCOUNT,
                MainSettingsSection.INTERFACE,
                MainSettingsSection.LOGGING
            ),
            MainSettingsLayoutPolicy.primarySectionOrder
        )
    }

    @Test fun defaultsMatchApprovedChatAppearance() {
        assertEquals(40, ChatAppearanceSettingsPolicy.DEFAULT_ALPHA_PERCENT)
        assertEquals(10, ChatAppearanceSettingsPolicy.DEFAULT_FONT_SIZE_SP)
        assertEquals(50, ChatAppearanceSettingsPolicy.DEFAULT_CARD_ALPHA_PERCENT)
        assertEquals(17, ChatAppearanceSettingsPolicy.DEFAULT_MESSAGE_FONT_SIZE_SP)
    }
    @Test fun metadataAlphaPercentIsClampedToSupportedRange() {
        assertEquals(0, ChatAppearanceSettingsPolicy.normalizeAlphaPercent(-1))
        assertEquals(40, ChatAppearanceSettingsPolicy.normalizeAlphaPercent(40))
        assertEquals(100, ChatAppearanceSettingsPolicy.normalizeAlphaPercent(101))
    }
    @Test fun metadataFontSizeIsClampedToSupportedRange() {
        assertEquals(8, ChatAppearanceSettingsPolicy.normalizeFontSizeSp(1))
        assertEquals(10, ChatAppearanceSettingsPolicy.normalizeFontSizeSp(10))
        assertEquals(16, ChatAppearanceSettingsPolicy.normalizeFontSizeSp(24))
    }
    @Test fun cardAlphaPercentIsClampedToSupportedRange() {
        assertEquals(0, ChatAppearanceSettingsPolicy.normalizeCardAlphaPercent(-1))
        assertEquals(50, ChatAppearanceSettingsPolicy.normalizeCardAlphaPercent(50))
        assertEquals(100, ChatAppearanceSettingsPolicy.normalizeCardAlphaPercent(101))
    }
    @Test fun messageFontSizeIsClampedToSupportedRange() {
        assertEquals(10, ChatAppearanceSettingsPolicy.normalizeMessageFontSizeSp(1))
        assertEquals(17, ChatAppearanceSettingsPolicy.normalizeMessageFontSizeSp(17))
        assertEquals(24, ChatAppearanceSettingsPolicy.normalizeMessageFontSizeSp(30))
    }
    @Test fun timestampForTodayContainsOnlyTime() {
        val zone = TimeZone.getTimeZone("UTC")
        assertEquals("14:35", ChatAppearanceSettingsPolicy.formatTimestamp(ts("2026-11-28T14:35:00Z"), ts("2026-11-28T16:09:00Z"), zone, Locale.US))
    }
    @Test fun timestampForYesterdayContainsDateAndTime() {
        val zone = TimeZone.getTimeZone("UTC")
        assertEquals("27.11.26 23:59", ChatAppearanceSettingsPolicy.formatTimestamp(ts("2026-11-27T23:59:00Z"), ts("2026-11-28T00:01:00Z"), zone, Locale.US))
    }
    @Test fun timestampForOlderMessageContainsDateAndTime() {
        val zone = TimeZone.getTimeZone("UTC")
        assertEquals("01.01.26 08:05", ChatAppearanceSettingsPolicy.formatTimestamp(ts("2026-01-01T08:05:00Z"), ts("2026-11-28T16:09:00Z"), zone, Locale.US))
    }
    @Test fun timestampUsesLocalCalendarDayRatherThanUtcDay() {
        val zone = TimeZone.getTimeZone("Europe/Berlin")
        assertEquals("00:30", ChatAppearanceSettingsPolicy.formatTimestamp(ts("2026-11-28T23:30:00Z"), ts("2026-11-29T00:45:00Z"), zone, Locale.US))
    }
    private fun ts(value: String): Long = java.time.Instant.parse(value).toEpochMilli()
}
