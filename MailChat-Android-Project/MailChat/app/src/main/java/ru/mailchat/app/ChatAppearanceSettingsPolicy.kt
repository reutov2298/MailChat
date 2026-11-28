package ru.mailchat.app

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object ChatAppearanceSettingsPolicy {
    const val DEFAULT_ALPHA_PERCENT = 40
    const val MIN_ALPHA_PERCENT = 0
    const val MAX_ALPHA_PERCENT = 100
    const val DEFAULT_FONT_SIZE_SP = 10
    const val MIN_FONT_SIZE_SP = 8
    const val MAX_FONT_SIZE_SP = 16
    const val DEFAULT_CARD_ALPHA_PERCENT = 50
    const val DEFAULT_MESSAGE_FONT_SIZE_SP = 17
    const val MIN_MESSAGE_FONT_SIZE_SP = 10
    const val MAX_MESSAGE_FONT_SIZE_SP = 24

    fun normalizeAlphaPercent(value: Int): Int = value.coerceIn(MIN_ALPHA_PERCENT, MAX_ALPHA_PERCENT)
    fun normalizeFontSizeSp(value: Int): Int = value.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
    fun normalizeCardAlphaPercent(value: Int): Int = value.coerceIn(MIN_ALPHA_PERCENT, MAX_ALPHA_PERCENT)
    fun normalizeMessageFontSizeSp(value: Int): Int = value.coerceIn(MIN_MESSAGE_FONT_SIZE_SP, MAX_MESSAGE_FONT_SIZE_SP)

    fun formatTimestamp(timestampMillis: Long, nowMillis: Long, timeZone: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault()): String {
        val messageDay = Calendar.getInstance(timeZone, locale).apply { timeInMillis = timestampMillis }
        val today = Calendar.getInstance(timeZone, locale).apply { timeInMillis = nowMillis }
        val sameDay = messageDay.get(Calendar.ERA) == today.get(Calendar.ERA) &&
            messageDay.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            messageDay.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        val pattern = if (sameDay) "HH:mm" else "dd.MM.yy HH:mm"
        return SimpleDateFormat(pattern, locale).apply { this.timeZone = timeZone }.format(Date(timestampMillis))
    }
}
