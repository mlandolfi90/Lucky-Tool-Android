package com.danielealbano.androidremotecontrolmcp.policy.amount

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal
import java.time.Duration

class AmountParserTest {
    @ParameterizedTest
    @MethodSource("rows")
    fun `scan finds the largest amount`(
        input: String,
        expectedMax: String?,
        ambiguous: Boolean,
        exceedsLimits: Boolean,
    ) {
        val scan = AmountParser.scan(input)

        if (expectedMax == null) {
            assertNull(scan.max)
        } else {
            assertNotNull(scan.max)
            assertEquals(0, BigDecimal(expectedMax).compareTo(scan.max))
        }
        assertEquals(ambiguous, scan.ambiguous)
        assertEquals(exceedsLimits, scan.exceedsLimits)
    }

    @Test
    fun `hostile input finishes quickly`() {
        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            AmountParser.scan("1.".repeat(4_999))
            AmountParser.scan("1 ".repeat(4_999))
            AmountParser.scan("1e" + "9".repeat(9_000))
        }
    }

    companion object {
        private fun row(
            input: String,
            max: String?,
            ambiguous: Boolean = false,
            exceedsLimits: Boolean = false,
        ): Arguments = Arguments.of(input, max, ambiguous, exceedsLimits)

        @JvmStatic
        fun rows(): List<Arguments> = groupingRows() + notationRows()

        private fun groupingRows(): List<Arguments> =
            listOf(
                row("", null),
                row("Pagar ahora", null),
                row("mil", null),
                row("cien mil", null),
                row("1500", "1500"),
                row("1.500,50", "1500.50"),
                row("1,500.50", "1500.50"),
                row("1.500", "1500", ambiguous = true),
                row("1,500", "1500", ambiguous = true),
                row("0.500", "500", ambiguous = true),
                row("1.5", "1.5"),
                row("1,50", "1.50"),
                row("1.5000", "1.5000"),
                row("1.500.000", "1500000"),
                row("1,500,000", "1500000"),
                row("1,50,000", "150000"),
                row("1.234.567,89", "1234567.89"),
                row("1,234,567.89", "1234567.89"),
                row("1.234,567.89", "123456789"),
            )

        private fun notationRows(): List<Arguments> =
            listOf(
                row("1'000.50", "1000.50"),
                row("1’000", "1000"),
                row("1 500", "1500"),
                row("1 500", "1500"),
                row("1 500", "1500"),
                row("12 345 678", "12345678"),
                row("1 50", "50"),
                row("1234 567", "1234"),
                row("4111 1111 1111 1111", "4111"),
                row("4111111111111111", "4111111111111111"),
                row("0170099220000067797370", "170099220000067797370"),
                row("-200", "200"),
                row("−200", "200"),
                row("(500)", "500"),
                row("$ 1.234,56", "1234.56"),
                row("USD 99", "99"),
                row("€1.000", "1000", ambiguous = true),
                row("5k", "5"),
                row("Pagar 100 y luego 2.500", "2500", ambiguous = true),
                row("2.500 y 3000", "3000"),
                row("3.000 y 3000", "3000"),
                row("1.500-2.000", "2000", ambiguous = true),
                row("1..5", "5"),
                row("1.500.", "1500", ambiguous = true),
                row("1.e5", "5"),
                row("١٬٥٠٠", "1500", ambiguous = true),
                row("٣٫٥", "3.5"),
                row("१२३", "123"),
                row("１２３", "123"),
                row("１，５００", "1500", ambiguous = true),
                row("²", "2"),
                row("1e6", "1000000"),
                row("2.5E3", "2500"),
                row("1.500e3", "1500000", ambiguous = true),
                row("2e-5", "0.00002"),
                row("1e-999", "1E-31"),
                row("1e30", "1E+30"),
                row("1e31", null, exceedsLimits = true),
                row("1e999999999", null, exceedsLimits = true),
                row("7 y 1e31", "7", exceedsLimits = true),
                row("1e", "1"),
                row("1e-", "1"),
                row("3em", "3"),
                row(
                    "9".repeat(10_000),
                    BigDecimal.TEN
                        .pow(10_000)
                        .subtract(BigDecimal.ONE)
                        .toPlainString(),
                ),
                row("9".repeat(10_001), null, exceedsLimits = true),
                row("ﷺ".repeat(3_000), null, exceedsLimits = true),
            )
    }
}
