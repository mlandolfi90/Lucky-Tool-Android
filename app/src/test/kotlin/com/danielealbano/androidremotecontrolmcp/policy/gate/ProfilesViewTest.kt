package com.danielealbano.androidremotecontrolmcp.policy.gate

import com.danielealbano.androidremotecontrolmcp.data.repository.ApprovalProfileRepository
import com.danielealbano.androidremotecontrolmcp.data.repository.ProfileStoreUnreadableException
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.BANK_PACKAGE
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.NOTES_PACKAGE
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfilesSnapshot
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class ProfilesViewTest {
    private val repository = mockk<ApprovalProfileRepository>()

    @Test
    fun `readable store exposes profiled and unreadable packages`() =
        runTest {
            val snapshot = PolicyFixtures.snapshotOf(PolicyFixtures.moneyProfile(), unreadable = setOf(NOTES_PACKAGE))
            coEvery { repository.current() } returns snapshot

            val view = ProfilesView.load(repository)

            assertEquals(setOf(BANK_PACKAGE, NOTES_PACKAGE), view.profiledPackages)
            assertTrue(view.anyProfile)
            assertSame(snapshot, view.snapshotFor(PolicyFixtures.subject(PolicyActionType.TYPE)))
        }

    @Test
    fun `empty store has no profiles`() =
        runTest {
            coEvery { repository.current() } returns ProfilesSnapshot.EMPTY

            val view = ProfilesView.load(repository)

            assertTrue(view.profiledPackages.isEmpty())
            assertFalse(view.anyProfile)
        }

    @Test
    fun `unreadable store is fail closed`() =
        runTest {
            coEvery { repository.current() } throws ProfileStoreUnreadableException(IOException())

            val view = ProfilesView.load(repository)
            val snapshot = view.snapshotFor(PolicyFixtures.subject(PolicyActionType.TYPE))

            assertEquals(setOf(ProfilesView.UNREADABLE_STORE_MARKER), view.profiledPackages)
            assertFalse(BANK_PACKAGE in view.profiledPackages)
            assertFalse(NOTES_PACKAGE in view.profiledPackages)
            assertTrue(view.anyProfile)
            assertTrue(BANK_PACKAGE in snapshot.unreadable)
            assertTrue(ProfilesView.UNREADABLE_STORE_MARKER in snapshot.unreadable)
            assertTrue(snapshot.hasAnyProfile)
        }

    @Test
    fun `other exceptions propagate`() =
        runTest {
            val illegal = IllegalStateException("boom")
            val io = IOException("disk")
            coEvery { repository.current() } throws illegal

            assertSame(illegal, assertThrows<IllegalStateException> { ProfilesView.load(repository) })

            coEvery { repository.current() } throws io

            assertSame(io, assertThrows<IOException> { ProfilesView.load(repository) })
        }
}
