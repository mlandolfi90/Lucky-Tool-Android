package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import app.cash.turbine.test
import com.danielealbano.androidremotecontrolmcp.data.repository.ApprovalProfileRepository
import com.danielealbano.androidremotecontrolmcp.data.repository.ProfileSaveResult
import com.danielealbano.androidremotecontrolmcp.data.repository.ProfileStoreStatus
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.BANK_PACKAGE
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.NOTES_PACKAGE
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalEventLogger
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalLogOutcome
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalLogRecord
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyExceptionKind
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfileTemplate
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfileTemplates
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfileValidationError
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfilesSnapshot
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalProfileEditorViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val snapshots = MutableStateFlow(ProfilesSnapshot.EMPTY)
    private val status = MutableStateFlow(ProfileStoreStatus.LOADED)
    private val repository = mockk<ApprovalProfileRepository>(relaxed = true)
    private val events = mockk<ApprovalEventLogger>(relaxed = true)
    private lateinit var viewModel: ApprovalProfileEditorViewModel
    private val bank = PolicyFixtures.moneyProfile()
    private val savedUpdate = ProfileSaveResult.Saved(PolicyFixtures.moneyProfile(), created = false)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { repository.observeProfiles() } returns snapshots
        every { repository.profiles } returns snapshots
        every { repository.storeStatus } returns status
        coEvery { repository.upsert(any()) } answers { ProfileSaveResult.Saved(firstArg(), created = true) }
        coEvery { repository.updateSettings(any(), any(), any(), any(), any()) } returns savedUpdate
        coEvery { repository.removeException(any(), any(), any()) } returns savedUpdate
        viewModel = ApprovalProfileEditorViewModel(repository, events, testDispatcher)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun profileEvent(outcome: ApprovalLogOutcome) = ApprovalLogRecord(outcome, packageName = BANK_PACKAGE)

    private fun assertNothingPersisted() {
        coVerify(exactly = 0) { repository.upsert(any()) }
        coVerify(exactly = 0) { repository.updateSettings(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `load with MONEY_APP creates an unsaved template draft`() =
        runTest {
            viewModel.state.test {
                viewModel.load(BANK_PACKAGE, ProfileTemplate.MONEY_APP)

                val state = expectMostRecentItem()
                assertTrue(state.loaded)
                assertTrue(state.isNew)
                assertEquals(ProfileTemplates.rulesFor(ProfileTemplate.MONEY_APP), state.draft?.rules)
                assertEquals("", state.draft?.maxAmountText)
                assertEquals(listOf(ProfileValidationError.MAX_AMOUNT_REQUIRED), state.errors)
                assertFalse(state.canSave)
            }
            assertNothingPersisted()
        }

    @Test
    fun `load with COMMON_APP is saveable at once`() =
        runTest {
            viewModel.state.test {
                viewModel.load(BANK_PACKAGE, ProfileTemplate.COMMON_APP)

                val state = expectMostRecentItem()
                assertTrue(state.errors.isEmpty())
                assertTrue(state.canSave)
            }
        }

    @Test
    fun `load prefers the stored profile over the template`() =
        runTest {
            snapshots.value = PolicyFixtures.snapshotOf(bank)

            viewModel.state.test {
                viewModel.load(BANK_PACKAGE, ProfileTemplate.COMMON_APP)

                val state = expectMostRecentItem()
                assertEquals(ProfileTemplate.MONEY_APP, state.draft?.template)
                assertEquals("10000", state.draft?.maxAmountText)
                assertFalse(state.isNew)
            }
        }

    @Test
    fun `load without stored profile or template has no draft`() =
        runTest {
            viewModel.state.test {
                viewModel.load(BANK_PACKAGE, null)

                val state = expectMostRecentItem()
                assertTrue(state.loaded)
                assertNull(state.draft)
                assertFalse(state.unreadable)
            }
        }

    @Test
    fun `load of an unreadable package exposes unreadable`() =
        runTest {
            snapshots.value = PolicyFixtures.snapshotOf(unreadable = setOf(BANK_PACKAGE))

            viewModel.state.test {
                viewModel.load(BANK_PACKAGE, null)

                val state = expectMostRecentItem()
                assertTrue(state.unreadable)
                assertNull(state.draft)
            }
        }

    @Test
    fun `load while the store is unreadable exposes storeUnreadable`() =
        runTest {
            status.value = ProfileStoreStatus.UNREADABLE

            viewModel.state.test {
                viewModel.load(BANK_PACKAGE, null)

                val state = expectMostRecentItem()
                assertTrue(state.storeUnreadable)
                assertNull(state.draft)
            }
        }

    @Test
    fun `load is idempotent`() =
        runTest {
            viewModel.state.test {
                viewModel.load(BANK_PACKAGE, ProfileTemplate.MONEY_APP)
                viewModel.load(NOTES_PACKAGE, ProfileTemplate.COMMON_APP)

                val state = expectMostRecentItem()
                assertEquals(BANK_PACKAGE, state.packageName)
                assertEquals(ProfileTemplate.MONEY_APP, state.draft?.template)
            }
        }

    @Test
    fun `setRule changes only that action type`() =
        runTest {
            viewModel.state.test {
                viewModel.load(BANK_PACKAGE, ProfileTemplate.MONEY_APP)
                viewModel.setRule(PolicyActionType.TYPE, RuleMode.FORBIDDEN)

                val rules = expectMostRecentItem().draft?.rules
                val expected =
                    ProfileTemplates.rulesFor(ProfileTemplate.MONEY_APP) + (PolicyActionType.TYPE to RuleMode.FORBIDDEN)
                assertEquals(expected, rules)
            }
        }

    @Test
    fun `setMaxAmount validates live`() =
        runTest {
            viewModel.state.test {
                viewModel.load(BANK_PACKAGE, ProfileTemplate.MONEY_APP)

                viewModel.setMaxAmount("150000")
                assertTrue(expectMostRecentItem().errors.isEmpty())
                viewModel.setMaxAmount("1,500")
                assertEquals(listOf(ProfileValidationError.MAX_AMOUNT_INVALID), expectMostRecentItem().errors)
                viewModel.setMaxAmount("1".repeat(40))
                assertEquals(30, expectMostRecentItem().draft?.maxAmountText?.length)
            }
        }

    @Test
    fun `addTier copies the last rule and stops at the cap`() =
        runTest {
            viewModel.state.test {
                viewModel.load(BANK_PACKAGE, ProfileTemplate.MONEY_APP)

                viewModel.addTier()
                assertEquals(
                    RuleMode.ASK_IN_CHAT,
                    expectMostRecentItem()
                        .draft
                        ?.tiers
                        ?.single()
                        ?.mode,
                )
                viewModel.setTier(0, TierDraft("", RuleMode.NOTIFY))
                viewModel.addTier()
                assertEquals(
                    RuleMode.NOTIFY,
                    expectMostRecentItem()
                        .draft
                        ?.tiers
                        ?.last()
                        ?.mode,
                )
                repeat(5) { viewModel.addTier() }
                assertEquals(5, expectMostRecentItem().draft?.tiers?.size)
            }
        }

    @Test
    fun `setTier ignores an out-of-range index and removeTier removes one`() =
        runTest {
            viewModel.state.test {
                viewModel.load(BANK_PACKAGE, ProfileTemplate.MONEY_APP)
                repeat(3) { viewModel.addTier() }

                viewModel.setTier(0, TierDraft("100", RuleMode.NOTIFY))
                viewModel.setTier(5, TierDraft("999", RuleMode.FORBIDDEN))
                val tiers = expectMostRecentItem().draft?.tiers.orEmpty()
                assertEquals(TierDraft("100", RuleMode.NOTIFY), tiers[0])
                assertEquals(3, tiers.size)

                viewModel.setTier(2, TierDraft("300", RuleMode.NOTIFY))
                viewModel.removeTier(1)
                assertEquals(listOf("100", "300"), expectMostRecentItem().draft?.tiers?.map { it.amountText })
            }
        }

    @Test
    fun `tier above max amount is reported`() =
        runTest {
            viewModel.state.test {
                viewModel.load(BANK_PACKAGE, ProfileTemplate.MONEY_APP)
                viewModel.setMaxAmount("100")
                viewModel.addTier()
                viewModel.setTier(0, TierDraft("200", RuleMode.ASK_IN_CHAT))

                assertTrue(ProfileValidationError.TIER_ABOVE_MAX_AMOUNT in expectMostRecentItem().errors)
            }
        }

    @Test
    fun `save of a new profile upserts canonical values and logs profile_created`() =
        runTest {
            viewModel.state.test {
                viewModel.load(BANK_PACKAGE, ProfileTemplate.MONEY_APP)
                viewModel.setMaxAmount("1500.50")
                viewModel.addTier()
                viewModel.setTier(0, TierDraft("100", RuleMode.ASK_IN_CHAT))

                viewModel.save()

                assertEquals(SaveStatus.SAVED, expectMostRecentItem().saveStatus)
            }
            coVerify {
                repository.upsert(
                    match {
                        it.maxAmount == "1500.5" &&
                            it.amountTiers.single().upTo == "100" &&
                            it.notAmountFields.isEmpty()
                    },
                )
            }
            coVerify(exactly = 0) { repository.updateSettings(any(), any(), any(), any(), any()) }
            verify { events.log(profileEvent(ApprovalLogOutcome.PROFILE_CREATED), null, null) }
        }

    @Test
    fun `save of a stored profile uses updateSettings and logs profile_updated`() =
        runTest {
            snapshots.value = PolicyFixtures.snapshotOf(bank)

            viewModel.state.test {
                viewModel.load(BANK_PACKAGE, null)
                viewModel.setMaxAmount("2000")
                viewModel.save()

                expectMostRecentItem()
            }
            coVerify {
                repository.updateSettings(BANK_PACKAGE, ProfileTemplate.MONEY_APP, bank.rules, "2000", emptyList())
            }
            coVerify(exactly = 0) { repository.upsert(any()) }
            verify { events.log(profileEvent(ApprovalLogOutcome.PROFILE_UPDATED), null, null) }
        }

    @Test
    fun `save maps every failure to its status and logs nothing`() =
        runTest {
            val failures =
                mapOf(
                    ProfileSaveResult.StoreUnavailable to SaveStatus.STORE_UNAVAILABLE,
                    ProfileSaveResult.ProfileLimitReached to SaveStatus.PROFILE_LIMIT_REACHED,
                    ProfileSaveResult.NotFound to SaveStatus.NOT_FOUND,
                    ProfileSaveResult.Invalid(listOf(ProfileValidationError.MAX_AMOUNT_REQUIRED)) to
                        SaveStatus.REJECTED,
                )

            viewModel.state.test {
                viewModel.load(BANK_PACKAGE, ProfileTemplate.COMMON_APP)
                failures.forEach { (result, expected) ->
                    coEvery { repository.upsert(any()) } returns result

                    viewModel.save()

                    assertEquals(expected, expectMostRecentItem().saveStatus)
                }
            }
            verify(exactly = 0) { events.log(any(), any(), any()) }
        }

    @Test
    fun `the next edit after a failed save resets IDLE`() =
        runTest {
            coEvery { repository.upsert(any()) } returns ProfileSaveResult.StoreUnavailable

            viewModel.state.test {
                viewModel.load(BANK_PACKAGE, ProfileTemplate.COMMON_APP)
                viewModel.save()
                assertEquals(SaveStatus.STORE_UNAVAILABLE, expectMostRecentItem().saveStatus)

                viewModel.setRule(PolicyActionType.TYPE, RuleMode.NOTIFY)

                assertEquals(SaveStatus.IDLE, expectMostRecentItem().saveStatus)
            }
        }

    @Test
    fun `save does nothing without a valid draft`() =
        runTest {
            viewModel.state.test {
                viewModel.save()
                assertNothingPersisted()

                viewModel.load(BANK_PACKAGE, ProfileTemplate.MONEY_APP)
                viewModel.save()

                assertEquals(SaveStatus.IDLE, expectMostRecentItem().saveStatus)
            }
            coVerify(exactly = 0) { repository.upsert(any()) }
        }

    @Test
    fun `save runs once while a save is in flight`() =
        runTest {
            val gate = CompletableDeferred<ProfileSaveResult>()
            coEvery { repository.upsert(any()) } coAnswers { gate.await() }

            viewModel.state.test {
                viewModel.load(BANK_PACKAGE, ProfileTemplate.COMMON_APP)
                val before = expectMostRecentItem().draft

                viewModel.save()
                viewModel.save()
                coVerify(exactly = 1) { repository.upsert(any()) }
                assertEquals(SaveStatus.SAVING, expectMostRecentItem().saveStatus)

                viewModel.setRule(PolicyActionType.TYPE, RuleMode.FORBIDDEN)
                assertEquals(before, viewModel.state.value.draft)

                gate.complete(ProfileSaveResult.Saved(PolicyFixtures.commonProfile(BANK_PACKAGE), created = true))
                val state = expectMostRecentItem()
                assertEquals(SaveStatus.SAVED, state.saveStatus)
                assertFalse(state.canSave)
            }
        }

    @Test
    fun `removeException delegates at once and logs exception_removed`() =
        runTest {
            val selector = PolicyFixtures.resourceId("id/a")
            snapshots.value = PolicyFixtures.snapshotOf(PolicyFixtures.moneyProfile(notAmountFields = listOf(selector)))

            viewModel.state.test {
                viewModel.load(BANK_PACKAGE, null)
                viewModel.removeException(PolicyExceptionKind.NOT_AMOUNT_FIELD, selector)
                expectMostRecentItem()
            }
            coVerify { repository.removeException(BANK_PACKAGE, PolicyExceptionKind.NOT_AMOUNT_FIELD, selector) }
            assertNothingPersisted()
            verify { events.log(profileEvent(ApprovalLogOutcome.EXCEPTION_REMOVED), null, null) }
        }

    @Test
    fun `removeException of a selector that is not listed logs nothing`() =
        runTest {
            snapshots.value = PolicyFixtures.snapshotOf(bank)

            viewModel.state.test {
                viewModel.load(BANK_PACKAGE, null)
                viewModel.removeException(PolicyExceptionKind.NOT_AMOUNT_FIELD, PolicyFixtures.resourceId("id/zz"))
                expectMostRecentItem()
            }
            coVerify { repository.removeException(BANK_PACKAGE, any(), any()) }
            verify(exactly = 0) { events.log(any(), any(), any()) }
        }

    @Test
    fun `removeException store failure shows the error until dismissed`() =
        runTest {
            snapshots.value = PolicyFixtures.snapshotOf(bank)
            coEvery { repository.removeException(any(), any(), any()) } returns ProfileSaveResult.StoreUnavailable

            viewModel.state.test {
                viewModel.load(BANK_PACKAGE, null)
                viewModel.removeException(PolicyExceptionKind.NOT_AMOUNT_FIELD, PolicyFixtures.resourceId("id/a"))
                assertTrue(expectMostRecentItem().removeFailed)
                viewModel.dismissRemoveError()
                assertFalse(expectMostRecentItem().removeFailed)

                coEvery { repository.removeException(any(), any(), any()) } returns ProfileSaveResult.NotFound
                viewModel.removeException(PolicyExceptionKind.NOT_AMOUNT_FIELD, PolicyFixtures.resourceId("id/a"))
                assertFalse(viewModel.state.value.removeFailed)
            }
        }

    @Test
    fun `removeException before load does nothing`() =
        runTest {
            viewModel.removeException(PolicyExceptionKind.NOT_AMOUNT_FIELD, PolicyFixtures.resourceId("id/a"))

            coVerify(exactly = 0) { repository.removeException(any(), any(), any()) }
        }

    @Test
    fun `exception lists follow the live snapshot`() =
        runTest {
            val selector = PolicyFixtures.resourceId("id/b")
            val stored = PolicyFixtures.moneyProfile(notActionButtons = listOf(selector))
            snapshots.value = PolicyFixtures.snapshotOf(stored)

            viewModel.state.test {
                viewModel.load(BANK_PACKAGE, null)
                assertEquals(listOf(selector), expectMostRecentItem().notActionButtons.map { it.selector })

                snapshots.value = PolicyFixtures.snapshotOf(bank)
                assertTrue(expectMostRecentItem().notActionButtons.isEmpty())
            }
        }
}
