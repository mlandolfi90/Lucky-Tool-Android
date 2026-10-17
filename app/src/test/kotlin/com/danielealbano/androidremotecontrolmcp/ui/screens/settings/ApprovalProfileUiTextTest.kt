package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfileTemplate
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfileValidationError
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ManualPackageError
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.SaveStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApprovalProfileUiTextTest {
    private fun assertDistinct(
        ids: List<Int>,
        size: Int,
    ) {
        assertTrue(ids.all { it != 0 })
        assertEquals(size, ids.distinct().size)
    }

    @Test
    fun `every enum value has a distinct text`() {
        assertDistinct(ProfileValidationError.entries.map { it.messageRes() }, ProfileValidationError.entries.size)
        assertDistinct(ManualPackageError.entries.map { it.messageRes() }, ManualPackageError.entries.size)
        assertDistinct(ProfileTemplate.entries.map { it.labelRes() }, ProfileTemplate.entries.size)
        assertDistinct(ProfileTemplate.entries.map { it.descriptionRes() }, ProfileTemplate.entries.size)
        assertDistinct(PolicyActionType.entries.map { it.descriptionRes() }, PolicyActionType.entries.size)
    }

    @Test
    fun `failureMessageRes is null exactly for non-failures`() {
        assertEquals(
            listOf(SaveStatus.IDLE, SaveStatus.SAVING, SaveStatus.SAVED),
            SaveStatus.entries.filter { it.failureMessageRes() == null },
        )
        val failures = SaveStatus.entries.mapNotNull { it.failureMessageRes() }
        assertDistinct(failures, 4)
    }

    @Test
    fun `DEFAULT_TEMPLATE is MONEY_APP`() {
        assertEquals(ProfileTemplate.MONEY_APP, DEFAULT_TEMPLATE)
    }
}
