package ru.mailchat.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class McProtocolBase64Test {
    @Test
    fun standardBase64AcceptsMimeTransportWhitespace() {
        assertEquals(
            "YWJjZA==",
            McProtocol.normalizeBase64Transport("YWJj\r\n ZA==", "x-mc-public-key", false)
        )
    }

    @Test
    fun urlSafeBase64AcceptsMimeFolding() {
        assertEquals("_w", McProtocol.normalizeBase64Transport("_\nw", "x-mc-text-base64", true))
    }

    @Test
    fun standardBase64RejectsIllegalAlphabetCharacters() {
        assertThrows(IllegalArgumentException::class.java) {
            McProtocol.normalizeBase64Transport("YWJj%=", "x-mc-public-key", false)
        }
    }

    @Test
    fun urlSafeBase64RejectsStandardAlphabetCharacters() {
        assertThrows(IllegalArgumentException::class.java) {
            McProtocol.normalizeBase64Transport("+w==", "x-mc-text-base64", true)
        }
    }

    @Test
    fun base64RejectsImpossibleLengthAndInvalidPadding() {
        assertThrows(IllegalArgumentException::class.java) {
            McProtocol.normalizeBase64Transport("A", "x-mc-signature", false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            McProtocol.normalizeBase64Transport("YW=J", "x-mc-signature", false)
        }
    }
}
