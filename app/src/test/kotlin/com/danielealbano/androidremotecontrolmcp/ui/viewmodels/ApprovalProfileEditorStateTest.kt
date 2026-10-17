package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import com.danielealbano.androidremotecontrolmcp.data.repository.ProfileSaveResult
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.BANK_PACKAGE
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfileTemplate
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfileValidationError
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfilesSnapshot
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ApprovalProfileEditorStateTest {
    private val bank = PolicyFixtures.moneyProfile()

    @ParameterizedTest
    @ValueSource(strings = ["1500", " 1500 ", "99.90", "0.00000001", "1.500", "007"])
    fun `isValidAmountInput accepts plain decimals`(input: String) {
        assertTrue(isValidAmountInput(input))
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["1,5", "1,500", "1 500", "-5", "+5", "1e3", "5.", ".5", "0", "", "abc", "١٢٣", "0.000000001"],
    )
    fun `isValidAmountInput rejects everything else`(input: String) {
        assertFalse(isValidAmountInput(input))
    }

    @Test
    fun `amountReadingOrNull shows the stored form only when it differs`() {
        assertEquals("1.5", amountReadingOrNull("1.500"))
        assertEquals("1500.5", amountReadingOrNull("1500.50"))
        assertNull(amountReadingOrNull("1500"))
        assertNull(amountReadingOrNull("abc"))
    }

    @Test
    fun `toDraft and toProfile round-trip a stored profile`() {
        val stored = PolicyFixtures.moneyProfile(amountTiers = listOf(PolicyFixtures.tier("100", RuleMode.ASK_IN_CHAT)))

        assertEquals(stored.copy(updatedAtMs = 0L), stored.toDraft().toProfile())
    }

    @Test
    fun `toProfile canonicalizes amounts and maps blank max to null`() {
        val draft =
            bank.toDraft().copy(maxAmountText = "1500.50", tiers = listOf(TierDraft(" 100 ", RuleMode.ASK_IN_CHAT)))

        val profile = draft.toProfile()

        assertEquals("1500.5", profile.maxAmount)
        assertEquals("100", profile.amountTiers.single().upTo)
        assertNull(draft.copy(maxAmountText = "  ").toProfile().maxAmount)
    }

    @Test
    fun `toProfile never carries exceptions`() {
        val withException = PolicyFixtures.moneyProfile(notAmountFields = listOf(PolicyFixtures.resourceId("id/a")))

        val profile = withException.toDraft().toProfile()

        assertTrue(profile.notAmountFields.isEmpty())
        assertTrue(profile.notActionButtons.isEmpty())
    }

    @Test
    fun `validateDraft uses the validator`() {
        val draft = bank.toDraft()

        assertEquals(
            listOf(ProfileValidationError.MAX_AMOUNT_INVALID),
            validateDraft(draft.copy(maxAmountText = "1,500")),
        )
        assertTrue(
            ProfileValidationError.TIER_AMOUNT_INVALID in
                validateDraft(draft.copy(tiers = listOf(TierDraft("", RuleMode.ASK_IN_CHAT)))),
        )
        assertEquals(listOf(ProfileValidationError.MAX_AMOUNT_REQUIRED), validateDraft(draft.copy(maxAmountText = "")))
    }

    @Test
    fun `toSaveStatus maps every result`() {
        assertEquals(SaveStatus.SAVED, ProfileSaveResult.Saved(bank, created = true).toSaveStatus())
        assertEquals(SaveStatus.REJECTED, ProfileSaveResult.Invalid(emptyList()).toSaveStatus())
        assertEquals(SaveStatus.REJECTED, ProfileSaveResult.ExceptionLimitReached.toSaveStatus())
        assertEquals(SaveStatus.PROFILE_LIMIT_REACHED, ProfileSaveResult.ProfileLimitReached.toSaveStatus())
        assertEquals(SaveStatus.NOT_FOUND, ProfileSaveResult.NotFound.toSaveStatus())
        assertEquals(SaveStatus.STORE_UNAVAILABLE, ProfileSaveResult.StoreUnavailable.toSaveStatus())
    }

    @Test
    fun `isFailure marks only the failure statuses`() {
        assertEquals(
            listOf(
                SaveStatus.STORE_UNAVAILABLE,
                SaveStatus.PROFILE_LIMIT_REACHED,
                SaveStatus.NOT_FOUND,
                SaveStatus.REJECTED,
            ),
            SaveStatus.entries.filter { it.isFailure },
        )
    }

    @Test
    fun `buildEditorState without target is the default state`() {
        assertEquals(ProfileEditorUiState(), buildEditorState(EditorSession(), ProfilesSnapshot.EMPTY, false))
    }

    @Test
    fun `buildEditorState flags unreadable only without a stored profile`() {
        val session = EditorSession(target = EditorTarget(BANK_PACKAGE, null), loaded = true)

        val unreadable = buildEditorState(session, PolicyFixtures.snapshotOf(unreadable = setOf(BANK_PACKAGE)), false)
        val stored = buildEditorState(session, PolicyFixtures.snapshotOf(bank), false)

        assertTrue(unreadable.unreadable && unreadable.isNew)
        assertFalse(stored.unreadable || stored.isNew)
    }

    @Test
    fun `buildEditorState takes exceptions from the snapshot`() {
        val stored = PolicyFixtures.moneyProfile(notActionButtons = listOf(PolicyFixtures.resourceId("id/b")))
        val session =
            EditorSession(target = EditorTarget(BANK_PACKAGE, null), loaded = true, draft = bank.toDraft())

        val state = buildEditorState(session, PolicyFixtures.snapshotOf(stored), false)

        assertEquals(stored.notActionButtons, state.notActionButtons)
    }

    @Test
    fun `canSave is false without draft, with errors, while saving and after saved`() {
        val valid = ProfileEditorUiState(draft = bank.toDraft())

        assertFalse(valid.copy(draft = null).canSave)
        assertFalse(valid.copy(errors = listOf(ProfileValidationError.MAX_AMOUNT_REQUIRED)).canSave)
        assertFalse(valid.copy(saveStatus = SaveStatus.SAVING).canSave)
        assertFalse(valid.copy(saveStatus = SaveStatus.SAVED).canSave)
        assertTrue(valid.canSave)
    }

    @Test
    fun `drafts redact amounts in toString`() {
        val draft = bank.toDraft().copy(maxAmountText = "123456")
        val tier = TierDraft("98765", RuleMode.NOTIFY)

        assertFalse(draft.toString().contains("123456"))
        assertFalse(tier.toString().contains("98765"))
        assertEquals(ProfileTemplate.MONEY_APP, draft.template)
    }
}
