package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import app.cash.turbine.test
import com.danielealbano.androidremotecontrolmcp.data.repository.ApprovalProfileRepository
import com.danielealbano.androidremotecontrolmcp.data.repository.ProfileDeleteResult
import com.danielealbano.androidremotecontrolmcp.data.repository.ProfileStoreStatus
import com.danielealbano.androidremotecontrolmcp.data.repository.ProfileStoreUnreadableException
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.BANK_PACKAGE
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.NOTES_PACKAGE
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.OWN_PACKAGE
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalEventLogger
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalLogOutcome
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalLogRecord
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfilesSnapshot
import com.danielealbano.androidremotecontrolmcp.services.apps.AppIconCache
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalProfilesViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val snapshots = MutableStateFlow(ProfilesSnapshot.EMPTY)
    private val status = MutableStateFlow(ProfileStoreStatus.LOADED)
    private val repository = mockk<ApprovalProfileRepository>(relaxed = true)
    private val appIconCache = mockk<AppIconCache>(relaxed = true)
    private val events = mockk<ApprovalEventLogger>(relaxed = true)
    private lateinit var viewModel: ApprovalProfilesViewModel
    private val bank = PolicyFixtures.moneyProfile()
    private val notes = PolicyFixtures.commonProfile()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { repository.observeProfiles() } returns snapshots
        every { repository.storeStatus } returns status
        coEvery { repository.delete(any()) } returns ProfileDeleteResult.DELETED
        every { appIconCache.getLaunchableApps() } returns
            listOf(BANK_PACKAGE to "Bank", NOTES_PACKAGE to "Notes", OWN_PACKAGE to "Remote")
        every { appIconCache.getAll() } returns emptyMap()
        every { appIconCache.refresh(any()) } answers { firstArg<() -> Unit>().invoke() }
        viewModel = ApprovalProfilesViewModel(repository, appIconCache, events, OWN_PACKAGE, testDispatcher)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `listState lists profiles with app names`() =
        runTest {
            viewModel.loadApps()
            snapshots.value = PolicyFixtures.snapshotOf(notes, bank)

            viewModel.listState.test {
                assertEquals(listOf("Bank", "Notes"), expectMostRecentItem().items.map { it.appName })
            }
        }

    @Test
    fun `listState follows snapshot changes`() =
        runTest {
            viewModel.loadApps()

            viewModel.listState.test {
                assertTrue(expectMostRecentItem().items.isEmpty())
                snapshots.value = PolicyFixtures.snapshotOf(bank)
                assertEquals(listOf(BANK_PACKAGE), expectMostRecentItem().items.map { it.packageName })
            }
        }

    @Test
    fun `listState flags an unreadable store`() =
        runTest {
            viewModel.listState.test {
                status.value = ProfileStoreStatus.UNREADABLE
                assertTrue(expectMostRecentItem().storeUnreadable)
                status.value = ProfileStoreStatus.LOADED
                assertFalse(expectMostRecentItem().storeUnreadable)
            }
        }

    @Test
    fun `retryLoad survives an unreadable store`() =
        runTest {
            coEvery { repository.current() } throws ProfileStoreUnreadableException(IOException())

            viewModel.retryLoad()

            coVerify { repository.current() }
        }

    @Test
    fun `pickerApps hides profiled, unreadable and own package`() =
        runTest {
            viewModel.loadApps()
            snapshots.value = PolicyFixtures.snapshotOf(bank, unreadable = setOf(NOTES_PACKAGE))

            viewModel.pickerApps.test {
                assertTrue(expectMostRecentItem().isEmpty())
            }
        }

    @Test
    fun `pickerApps shows apps without a profile`() =
        runTest {
            viewModel.loadApps()

            viewModel.pickerApps.test {
                assertEquals(listOf("Bank", "Notes"), expectMostRecentItem().map { it.appName })
            }
        }

    @Test
    fun `loadApps publishes the cache list and the refresh result`() =
        runTest {
            viewModel.loadApps()

            verify(exactly = 2) { appIconCache.getLaunchableApps() }
            assertEquals(appIconCache.getAll(), viewModel.appIcons.value)
        }

    @Test
    fun `delete logs profile_deleted when the key was removed`() =
        runTest {
            viewModel.listState.test {
                viewModel.delete(BANK_PACKAGE)

                coVerify { repository.delete(BANK_PACKAGE) }
                val record = ApprovalLogRecord(ApprovalLogOutcome.PROFILE_DELETED, packageName = BANK_PACKAGE)
                verify { events.log(record, null, null) }
                assertFalse(expectMostRecentItem().deleteFailed)
            }
        }

    @Test
    fun `delete of a missing package logs nothing`() =
        runTest {
            coEvery { repository.delete(any()) } returns ProfileDeleteResult.NOT_FOUND

            viewModel.listState.test {
                viewModel.delete(BANK_PACKAGE)

                verify(exactly = 0) { events.log(any(), any(), any()) }
                assertFalse(expectMostRecentItem().deleteFailed)
            }
        }

    @Test
    fun `delete store failure shows the error until dismissed`() =
        runTest {
            coEvery { repository.delete(any()) } returns ProfileDeleteResult.STORE_UNAVAILABLE

            viewModel.listState.test {
                viewModel.delete(BANK_PACKAGE)
                assertTrue(expectMostRecentItem().deleteFailed)
                verify(exactly = 0) { events.log(any(), any(), any()) }

                viewModel.dismissDeleteError()
                assertFalse(expectMostRecentItem().deleteFailed)
            }
        }

    @Test
    fun `validateManualPackage trims and validates against the live snapshot`() =
        runTest {
            snapshots.value = PolicyFixtures.snapshotOf(bank)

            viewModel.listState.test {
                expectMostRecentItem()

                assertEquals(ManualPackageError.EMPTY, viewModel.validateManualPackage("   "))
                assertEquals(ManualPackageError.OWN_PACKAGE, viewModel.validateManualPackage(OWN_PACKAGE))
                assertEquals(ManualPackageError.INVALID_FORMAT, viewModel.validateManualPackage("bank"))
                assertEquals(ManualPackageError.ALREADY_PROFILED, viewModel.validateManualPackage(BANK_PACKAGE))
                assertNull(viewModel.validateManualPackage("  com.example.new  "))
            }
        }
}
