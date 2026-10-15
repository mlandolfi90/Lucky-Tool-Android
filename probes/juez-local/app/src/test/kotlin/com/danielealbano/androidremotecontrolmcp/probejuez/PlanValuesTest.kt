package com.danielealbano.androidremotecontrolmcp.probejuez

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlanValuesTest {
    @Test
    fun `amounts compare by number and currency`() {
        // Act & Assert
        assertTrue(PlanValues.same("20.000 Bs", "20000 Bs"))
        assertTrue(PlanValues.same("Bs. 20.000,00", "20000 Bs"))
        assertTrue(PlanValues.same("20,000.00 bs", "20000 Bs"))
        assertFalse(PlanValues.same("2000 Bs", "20000 Bs"))
        assertFalse(PlanValues.same("200000 Bs", "20000 Bs"))
        assertFalse(PlanValues.same("20000 USDT", "20000 Bs"))
        assertFalse(PlanValues.same("20000", "20000 Bs"))
        assertFalse(PlanValues.same("hasta 20000 Bs", "20000 Bs"))
        assertFalse(PlanValues.same("NINGUNO", "20000 Bs"))
    }

    @Test
    fun `text compares without case or the marks around it and keeps every other character`() {
        // Act & Assert
        assertTrue(PlanValues.same("«cambioapp»", "CambioApp"))
        assertTrue(PlanValues.same("Nadie", "NINGUNO"))
        assertTrue(PlanValues.same("ninguno.", "NINGUNO"))
        assertFalse(PlanValues.same("juanp2p_", "juanp2p"))
        assertFalse(PlanValues.same("juanp2p (comprador)", "juanp2p"))
        assertFalse(PlanValues.same("Ana María", "Ana"))
        assertFalse(PlanValues.same("20000 Bs", "NINGUNO"))
    }
}
