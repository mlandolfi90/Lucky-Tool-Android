package com.danielealbano.androidremotecontrolmcp.policy.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProfileTemplatesTest {
    @Test
    fun `money app rules match the owner template`() {
        assertEquals(
            mapOf(
                PolicyActionType.READ to RuleMode.RUN,
                PolicyActionType.NAVIGATE to RuleMode.RUN,
                PolicyActionType.TYPE to RuleMode.ASK_IN_CHAT,
                PolicyActionType.ACTION_BUTTON to RuleMode.NOTIFY,
            ),
            ProfileTemplates.rulesFor(ProfileTemplate.MONEY_APP),
        )
    }

    @Test
    fun `common app rules match the owner template`() {
        assertEquals(
            mapOf(
                PolicyActionType.READ to RuleMode.RUN,
                PolicyActionType.NAVIGATE to RuleMode.RUN,
                PolicyActionType.TYPE to RuleMode.RUN,
                PolicyActionType.ACTION_BUTTON to RuleMode.ASK_IN_CHAT,
            ),
            ProfileTemplates.rulesFor(ProfileTemplate.COMMON_APP),
        )
    }

    @Test
    fun `newProfile starts without limits or exceptions`() {
        val profile = ProfileTemplates.newProfile("com.example.bank", ProfileTemplate.MONEY_APP, NOW_MS)

        assertNull(profile.maxAmount)
        assertTrue(profile.amountTiers.isEmpty())
        assertTrue(profile.notAmountFields.isEmpty())
        assertTrue(profile.notActionButtons.isEmpty())
        assertEquals(NOW_MS, profile.updatedAtMs)
        assertTrue(PolicyActionType.entries.all { it in profile.rules })
    }

    @Test
    fun `new money profile does not validate until maxAmount is set`() {
        val profile = ProfileTemplates.newProfile("com.example.bank", ProfileTemplate.MONEY_APP, NOW_MS)

        assertEquals(
            listOf(ProfileValidationError.MAX_AMOUNT_REQUIRED),
            ApprovalProfileValidator.validate(profile),
        )
        assertTrue(ApprovalProfileValidator.validate(profile.copy(maxAmount = "5000")).isEmpty())
    }

    @Test
    fun `new common profile validates`() {
        val profile = ProfileTemplates.newProfile("com.example.notes", ProfileTemplate.COMMON_APP, NOW_MS)

        assertTrue(ApprovalProfileValidator.validate(profile).isEmpty())
    }

    private companion object {
        const val NOW_MS = 1_234L
    }
}
