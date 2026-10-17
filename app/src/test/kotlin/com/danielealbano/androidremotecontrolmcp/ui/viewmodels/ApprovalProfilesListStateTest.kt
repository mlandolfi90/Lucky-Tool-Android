package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.BANK_PACKAGE
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.NOTES_PACKAGE
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfileTemplate
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfilesSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApprovalProfilesListStateTest {
    private val bank = PolicyFixtures.moneyProfile()
    private val notes = PolicyFixtures.commonProfile()
    private val own = PolicyFixtures.OWN_PACKAGE

    @Test
    fun `profileListItems names and sorts readable profiles`() {
        val items =
            profileListItems(
                PolicyFixtures.snapshotOf(bank, notes),
                listOf(BANK_PACKAGE to "zeta Bank", NOTES_PACKAGE to "Alpha Notes"),
            )

        assertEquals(listOf("Alpha Notes", "zeta Bank"), items.map { it.appName })
        assertEquals(listOf(ProfileTemplate.COMMON_APP, ProfileTemplate.MONEY_APP), items.map { it.template })
        assertTrue(items.none { it.unreadable })
    }

    @Test
    fun `profileListItems falls back to the package name`() {
        val hidden = PolicyFixtures.commonProfile("com.example.hidden")

        val items = profileListItems(PolicyFixtures.snapshotOf(bank, hidden), listOf(BANK_PACKAGE to "Zebra"))

        assertEquals(listOf("com.example.hidden", BANK_PACKAGE), items.map { it.packageName })
        assertNull(items.first().appName)
    }

    @Test
    fun `profileListItems lists unreadable packages without template`() {
        val item = profileListItems(PolicyFixtures.snapshotOf(unreadable = setOf(NOTES_PACKAGE)), emptyList()).single()

        assertTrue(item.unreadable)
        assertNull(item.template)
    }

    @Test
    fun `toPickerApps hides profiled, unreadable and own package`() {
        val snapshot = PolicyFixtures.snapshotOf(bank, unreadable = setOf(NOTES_PACKAGE))
        val apps = listOf(BANK_PACKAGE to "Bank", NOTES_PACKAGE to "Notes", own to "Remote")

        assertTrue(toPickerApps(snapshot, apps, own).isEmpty())
    }

    @Test
    fun `toPickerApps keeps apps without a profile in cache order`() {
        val apps = listOf(BANK_PACKAGE to "Bank", NOTES_PACKAGE to "Notes", own to "Remote")

        assertEquals(
            listOf(PickerApp(BANK_PACKAGE, "Bank"), PickerApp(NOTES_PACKAGE, "Notes")),
            toPickerApps(ProfilesSnapshot.EMPTY, apps, own),
        )
    }

    @Test
    fun `manualPackageError rejects empty, own, malformed and profiled names`() {
        val snapshot = PolicyFixtures.snapshotOf(bank, unreadable = setOf(NOTES_PACKAGE))

        assertEquals(ManualPackageError.EMPTY, manualPackageError("", snapshot, own))
        assertEquals(ManualPackageError.OWN_PACKAGE, manualPackageError(own, snapshot, own))
        listOf("bank", "com..bank", "1com.bank").forEach {
            assertEquals(ManualPackageError.INVALID_FORMAT, manualPackageError(it, snapshot, own), it)
        }
        assertEquals(ManualPackageError.ALREADY_PROFILED, manualPackageError(BANK_PACKAGE, snapshot, own))
        assertEquals(ManualPackageError.ALREADY_PROFILED, manualPackageError(NOTES_PACKAGE, snapshot, own))
    }

    @Test
    fun `manualPackageError accepts a new valid package`() {
        assertNull(manualPackageError("com.example.new", ProfilesSnapshot.EMPTY, own))
    }
}
