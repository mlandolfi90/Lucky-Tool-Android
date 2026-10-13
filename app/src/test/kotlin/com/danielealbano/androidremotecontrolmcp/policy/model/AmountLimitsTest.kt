package com.danielealbano.androidremotecontrolmcp.policy.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal

class AmountLimitsTest {
    @ParameterizedTest
    @MethodSource("validInputs")
    fun `parse accepts plain positive decimals`(
        input: String,
        expected: String,
    ) {
        val parsed = AmountLimits.parse(input)

        assertNotNull(parsed)
        assertEquals(0, BigDecimal(expected).compareTo(parsed))
    }

    @ParameterizedTest
    @MethodSource("invalidInputs")
    fun `parse rejects everything else`(input: String) {
        assertNull(AmountLimits.parse(input))
    }

    @Test
    fun `canonical strips trailing zeros without exponent`() {
        assertEquals("1500.5", AmountLimits.canonical(BigDecimal("1500.50")))
        assertEquals("1000", AmountLimits.canonical(BigDecimal("1E+3")))
        assertEquals("0.00000001", AmountLimits.canonical(BigDecimal("0.00000001")))
    }

    @Test
    fun `canonical output parses back to the same value`() {
        listOf("1500.50", "0.10000000", "10000").forEach { text ->
            val value = AmountLimits.parse(text)!!
            val reparsed = AmountLimits.parse(AmountLimits.canonical(value))

            assertNotNull(reparsed)
            assertEquals(0, value.compareTo(reparsed))
        }
    }

    companion object {
        private val THIRTY_DIGITS = "1".repeat(30)
        private val THIRTY_ONE_DIGITS = "1".repeat(31)

        @JvmStatic
        fun validInputs(): List<Arguments> =
            listOf(
                Arguments.of("1500", "1500"),
                Arguments.of("1500.50", "1500.50"),
                Arguments.of("0.01", "0.01"),
                Arguments.of("0.00000001", "0.00000001"),
                Arguments.of("1500.12345678", "1500.12345678"),
                Arguments.of("007", "7"),
                Arguments.of(THIRTY_DIGITS, THIRTY_DIGITS),
            )

        @JvmStatic
        fun invalidInputs(): List<String> =
            listOf(
                "0.000000001",
                "1500.123456789",
                "0",
                "0.00",
                "-5",
                "+5",
                "1e3",
                "1E+3",
                "1,5",
                "1.500,00",
                "1,500",
                "1 500",
                "1'500",
                "1.2.3",
                ".5",
                "5.",
                "",
                "abc",
                "１２３",
                "١٢٣",
                " 1500",
                "1500 ",
                THIRTY_ONE_DIGITS,
            )
    }
}
