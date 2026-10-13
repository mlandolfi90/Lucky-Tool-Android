package com.danielealbano.androidremotecontrolmcp.policy.subject

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ToolPolicyCatalogTest {
    private fun assertClass(
        expected: ToolPolicyClass,
        names: List<String>,
    ) {
        names.forEach { assertEquals(expected, ToolPolicyCatalog.classOf(it), it) }
    }

    @Test
    fun `catalog lists 59 distinct names`() {
        assertEquals(EXPECTED_NAMES, ToolPolicyCatalog.classes.size)
        assertEquals(ToolPolicyCatalog.classes.size, ToolPolicyCatalog.listedNameCount)
    }

    @Test
    fun `classOf returns null for an unknown tool`() {
        assertNull(ToolPolicyCatalog.classOf("nope"))
    }

    @Test
    fun `requireClass throws IllegalStateException naming the tool`() {
        val error = assertThrows<IllegalStateException> { ToolPolicyCatalog.requireClass("nope") }

        assertTrue(error.message.orEmpty().contains("'nope'"))
    }

    @Test
    fun `approval tools are NOT_APP_SCOPED`() {
        assertClass(
            ToolPolicyClass.NOT_APP_SCOPED,
            listOf(ToolPolicyCatalog.GET_APPROVAL_STATUS, ToolPolicyCatalog.CONFIRM_HUMAN_APPROVAL),
        )
    }

    @Test
    fun `file camera location sharing and global tools are NOT_APP_SCOPED`() {
        assertClass(
            ToolPolicyClass.NOT_APP_SCOPED,
            listOf(
                "list_storage_locations",
                "list_files",
                "read_file",
                "write_file",
                "append_file",
                "file_replace",
                "download_from_url",
                "delete_file",
                "list_cameras",
                "list_camera_photo_resolutions",
                "list_camera_video_resolutions",
                "take_camera_photo",
                "save_camera_photo",
                "save_camera_video",
                "get_location",
                "list_apps",
                "get_clipboard",
                "get_shared_content",
                "share_file_via_web",
            ),
        )
    }

    @Test
    fun `exit tools are EXEMPT_EXIT`() {
        assertClass(
            ToolPolicyClass.EXEMPT_EXIT,
            listOf("press_home", "press_recents", "open_notifications", "open_quick_settings", "dismiss_keyboard"),
        )
    }

    @Test
    fun `multi-window reads are SCREEN_READ`() {
        assertClass(
            ToolPolicyClass.SCREEN_READ,
            listOf("get_screen_state", "find_nodes", "wait_for_node", "wait_for_idle"),
        )
    }

    @Test
    fun `every ToolPolicyClass is used by at least one tool`() {
        assertTrue(ToolPolicyCatalog.classes.values.toSet().containsAll(ToolPolicyClass.entries))
    }

    @Test
    fun `only NOT_APP_SCOPED and EXEMPT_EXIT have ResolverKind NONE`() {
        ToolPolicyClass.entries.forEach { policyClass ->
            val expectNone =
                policyClass == ToolPolicyClass.NOT_APP_SCOPED || policyClass == ToolPolicyClass.EXEMPT_EXIT
            assertEquals(expectNone, policyClass.kind == ResolverKind.NONE, policyClass.name)
        }
    }

    private companion object {
        const val EXPECTED_NAMES = 59
    }
}
