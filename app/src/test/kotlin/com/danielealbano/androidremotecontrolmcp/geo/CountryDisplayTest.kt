package com.danielealbano.androidremotecontrolmcp.geo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.util.Locale

@DisplayName("CountryDisplay")
class CountryDisplayTest {
    @Test
    @DisplayName("maps a 2-letter code to its regional-indicator flag emoji")
    fun flag() {
        assertEquals("🇩🇪", CountryDisplay.flag("DE")) // 🇩🇪
        assertEquals("🇺🇸", CountryDisplay.flag("us")) // case-insensitive -> 🇺🇸
    }

    @Test
    @DisplayName("returns no flag for missing or non-letter codes")
    fun flagRejects() {
        assertNull(CountryDisplay.flag(null))
        assertNull(CountryDisplay.flag(""))
        assertNull(CountryDisplay.flag("X")) // wrong length
        assertNull(CountryDisplay.flag("U1")) // not two letters
    }

    @Test
    @DisplayName("resolves a localized country name and rejects non-country codes")
    fun name() {
        val germany = CountryDisplay.name("DE")
        assertNotNull(germany)
        assertNotEquals("DE", germany) // a real name, not the code echoed back
        assertNull(CountryDisplay.name(null))
        assertNull(CountryDisplay.name("U1"))
    }

    @Test
    fun `name never throws for any two-letter code`() {
        // Arrange
        val inputs = ALL_TWO_LETTER_CODES.flatMap { listOf(it, it.lowercase(), " $it ", "\t${it.lowercase()}\n") }

        // Act + Assert
        inputs.forEach { input ->
            assertDoesNotThrow("name(\"$input\") must not throw") { CountryDisplay.name(input) }
        }
    }

    @Test
    fun `name normalizes case and whitespace before resolving`() {
        // Act
        val padded = CountryDisplay.name(" us ")
        val canonical = CountryDisplay.name("US")
        val empty = CountryDisplay.name("")

        // Assert
        assertEquals(canonical, padded)
        assertNull(empty)
    }

    @Test
    fun `name matches an independent region lookup for every two-letter code`() {
        ALL_TWO_LETTER_CODES.forEach { code ->
            // Arrange
            val lookedUp = Locale.forLanguageTag("und-$code").getDisplayCountry(Locale.getDefault())
            val expected = lookedUp.takeIf { it.isNotEmpty() && !it.equals(code, ignoreCase = true) }

            // Act
            val actual = CountryDisplay.name(code)

            // Assert
            assertEquals(expected, actual, "name(\"$code\")")
        }
    }

    private companion object {
        val ALL_TWO_LETTER_CODES: List<String> = ('A'..'Z').flatMap { first -> ('A'..'Z').map { "$first$it" } }
    }
}
