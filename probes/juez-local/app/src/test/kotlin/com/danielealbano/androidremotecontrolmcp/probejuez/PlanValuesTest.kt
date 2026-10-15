package com.danielealbano.androidremotecontrolmcp.probejuez

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlanValuesTest {
    private fun sameAmount(
        read: String,
        planValue: String,
    ) = PlanValues.same(read, planValue, amount = true)

    private fun sameText(
        read: String,
        planValue: String,
    ) = PlanValues.same(read, planValue, amount = false)

    @Test
    fun `amounts compare by number and currency`() {
        // Act & Assert
        assertTrue(sameAmount("20.000 Bs", "20000 Bs"))
        assertTrue(sameAmount("Bs. 20.000,00", "20000 Bs"))
        assertTrue(sameAmount("20,000.00 bs", "20000 Bs"))
        assertTrue(sameAmount("20000 Bs.", "20000 Bs"))
        assertTrue(sameAmount("**20.000,00 Bs**", "20000 Bs"))
        assertTrue(sameAmount("NINGUNO", "NINGUNO"))
        assertFalse(sameAmount("2000 Bs", "20000 Bs"))
        assertFalse(sameAmount("200000 Bs", "20000 Bs"))
        assertFalse(sameAmount("20000 USDT", "20000 Bs"))
        assertFalse(sameAmount("20000", "20000 Bs"))
        assertFalse(sameAmount("+20.000,00 Bs", "20000 Bs"))
        assertFalse(sameAmount("-20.000,00 Bs", "20000 Bs"))
        assertFalse(sameAmount("hasta 20000 Bs", "20000 Bs"))
        assertFalse(sameAmount("b 20000 s", "20000 bs"))
        assertFalse(sameAmount("NINGUNO", "20000 Bs"))
    }

    @Test
    fun `amounts read their separators with the grouping checked`() {
        // Act & Assert
        assertTrue(sameAmount("20000,50 Bs", "20000.5 Bs"))
        assertTrue(sameAmount("15,50 USD", "15.5 USD"))
        assertTrue(sameAmount("1.234.567,89 Bs", "1234567.89 Bs"))
        assertTrue(sameAmount("20000.000 Bs", "20000 Bs"))
        assertTrue(sameAmount("1.250 USDT", "1250 USDT"))
        assertFalse(sameAmount("200.00,00 Bs", "20000 Bs"))
        assertFalse(sameAmount("2,0.000 Bs", "20 Bs"))
        assertFalse(sameAmount("20,00.00 Bs", "2000 Bs"))
        assertFalse(sameAmount("1.23.456 Bs", "123456 Bs"))
        assertFalse(PlanValues.isAmountOrNone("1.23.456 Bs"))
        assertFalse(PlanValues.isAmountOrNone("20000"))
        assertTrue(PlanValues.isAmountOrNone("20000 Bs"))
    }

    @Test
    fun `names with letters and digits compare as text, never as amounts`() {
        // Act & Assert
        assertTrue(sameText("Pedro99", "pedro99"))
        assertFalse(sameText("pedro099", "pedro99"))
        assertFalse(sameText("juan02000", "juan2000"))
        assertFalse(sameText("juan2.000", "juan2000"))
        assertFalse(sameText("Juan 2000", "juan2000"))
        assertFalse(sameText("juan2000.0", "juan2000"))
        assertFalse(sameText("user7", "user0007"))
        assertFalse(sameText("CambioApp02", "CambioApp2"))
        assertFalse(sameText("20.000,00 Bs", "20000 Bs"))
    }

    @Test
    fun `text drops only the pairs around the whole value and keeps every other character`() {
        // Act & Assert
        val quoted = listOf("«cambioapp»", "\"CambioApp\"", "“CambioApp”", "`CambioApp`")
        val bracketed = listOf("**CambioApp**", "(CambioApp)", "[CambioApp]")
        (quoted + bracketed).forEach { assertTrue(sameText(it, "CambioApp"), it) }
        assertTrue(sameText("**«CambioApp»**", "CambioApp"))
        val lookAlikes = listOf("juanp2p.", "juanp2p!", "¿juanp2p?", "juanp2p:", "<juanp2p>", "juanp2p_", "juan**p2p")
        (lookAlikes + "juanp2p (comprador)").forEach { assertFalse(sameText(it, "juanp2p"), it) }
        assertFalse(sameText("juan  p2p", "juan p2p"))
        assertFalse(sameText("Ana María", "Ana"))
        assertFalse(sameText("20000 Bs", "NINGUNO"))
    }

    @Test
    fun `only NINGUNO means no value`() {
        // Act & Assert
        listOf("NINGUNO", "ninguno", "**NINGUNO**", "«Ninguno»").forEach { assertTrue(PlanValues.isNone(it), it) }
        listOf("Nadie", "Ninguna", "ningún", "ninguno.", "NINGUNO (solo mira)").forEach {
            assertFalse(PlanValues.isNone(it), it)
            assertFalse(sameText(it, "NINGUNO"), it)
        }
    }

    @Test
    fun `inText finds a copied value written in the action as whole words`() {
        // Arrange
        val release =
            "app: CambioApp\nacción: tocar «Liberar»\nmonto en pantalla: 20.000,00 Bs\ncomprador en pantalla: juanp2p_"
        val typed = "acción: escribir «20000» en el campo «Monto (Bs)»"

        // Act & Assert
        assertTrue(PlanValues.inText("juanp2p_", release, amount = false))
        assertTrue(PlanValues.inText("cambioapp", release, amount = false))
        assertFalse(PlanValues.inText("juanp2p", release, amount = false))
        assertFalse(PlanValues.inText("pedro99", "comprador: pedro099", amount = false))
        assertFalse(PlanValues.inText("pedro99", "comprador: pedro990", amount = false))
        assertTrue(PlanValues.inText("TQ7m...9xY (externa)", "destino: TQ7m...9xY (externa)", amount = false))
        assertTrue(PlanValues.inText("20000 Bs", release, amount = true))
        assertTrue(PlanValues.inText("20000 Bs", typed, amount = true))
        assertFalse(PlanValues.inText("20000 Bs", "monto en pantalla: 50000 Bs", amount = true))
        assertFalse(PlanValues.inText("20000 USDT", release, amount = true))
        assertFalse(PlanValues.inText("20 Bs", "red: TRC20, monto: 540 Bs", amount = true))
    }
}
