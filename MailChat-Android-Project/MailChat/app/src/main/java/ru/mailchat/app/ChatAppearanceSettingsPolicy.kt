package ru.mailchat.app

internal object ChatAppearanceSettingsPolicy {
    const val DEFAULT_ALPHA_PERCENT = 80
    const val MIN_ALPHA_PERCENT = 0
    const val MAX_ALPHA_PERCENT = 100

    const val DEFAULT_FONT_SIZE_SP = 10
    const val MIN_FONT_SIZE_SP = 8
    const val MAX_FONT_SIZE_SP = 16

    const val DEFAULT_CARD_ALPHA_PERCENT = 100
    const val DEFAULT_MESSAGE_FONT_SIZE_SP = 16
    const val MIN_MESSAGE_FONT_SIZE_SP = 10
    const val MAX_MESSAGE_FONT_SIZE_SP = 24

    fun normalizeAlphaPercent(value: Int): Int =
        value.coerceIn(MIN_ALPHA_PERCENT, MAX_ALPHA_PERCENT)

    fun normalizeFontSizeSp(value: Int): Int =
        value.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)

    fun normalizeCardAlphaPercent(value: Int): Int =
        value.coerceIn(MIN_ALPHA_PERCENT, MAX_ALPHA_PERCENT)

    fun normalizeMessageFontSizeSp(value: Int): Int =
        value.coerceIn(MIN_MESSAGE_FONT_SIZE_SP, MAX_MESSAGE_FONT_SIZE_SP)
}
