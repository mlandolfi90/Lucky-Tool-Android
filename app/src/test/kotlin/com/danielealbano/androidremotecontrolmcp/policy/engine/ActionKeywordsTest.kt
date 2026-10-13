package com.danielealbano.androidremotecontrolmcp.policy.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ActionKeywordsTest {
    @ParameterizedTest
    @CsvSource(
        "Pagar, true",
        "PAGÁ AHORA, true",
        "Transferí $1.500, true",
        "Confirmar transferencia, true",
        "Place order, true",
        "CHECK OUT, true",
        "Top up, true",
        "pay, true",
        "Ｐａｙ, true",
        "Pagar1500, true",
        "Apagar, false",
        "Sender, false",
        "Payment history, false",
        "Place, false",
        "Order, false",
        "Top, false",
        "Movimientos, false",
        "Inicio, false",
        "'', false",
    )
    fun `matches action keywords as whole words`(
        label: String,
        expected: Boolean,
    ) {
        assertEquals(expected, ActionKeywords.matches(label))
    }

    @Test
    fun `keyword entries are stored folded`() {
        (ActionKeywords.SPANISH + ActionKeywords.ENGLISH).forEach {
            assertEquals(PolicyLabels.words(it).joinToString(" "), it)
        }
    }
}
