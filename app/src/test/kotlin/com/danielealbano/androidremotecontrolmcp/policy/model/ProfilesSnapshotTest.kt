package com.danielealbano.androidremotecontrolmcp.policy.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProfilesSnapshotTest {
    @Test
    fun `EMPTY has no profile`() {
        assertEquals(0, ProfilesSnapshot.EMPTY.storedCount)
        assertFalse(ProfilesSnapshot.EMPTY.hasAnyProfile)
    }

    @Test
    fun `unreadable counts as a stored profile`() {
        val snapshot = ProfilesSnapshot(emptyMap(), setOf("com.bank"))

        assertEquals(1, snapshot.storedCount)
        assertTrue(snapshot.hasAnyProfile)
    }
}
