package com.danielealbano.androidremotecontrolmcp.policy.subject

/**
 * Policy class of EVERY tool the server registers (un-prefixed names) plus the two approval tools. Every
 * registration must find its tool here ([requireClass]), so an unclassified tool fails closed.
 */
object ToolPolicyCatalog {
    const val GET_APPROVAL_STATUS = "get_approval_status"
    const val CONFIRM_HUMAN_APPROVAL = "confirm_human_approval"

    private val TOOLS_BY_CLASS: Map<ToolPolicyClass, List<String>> =
        mapOf(
            ToolPolicyClass.NOT_APP_SCOPED to
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
                    GET_APPROVAL_STATUS,
                    CONFIRM_HUMAN_APPROVAL,
                ),
            ToolPolicyClass.EXEMPT_EXIT to
                listOf(
                    "press_home",
                    "press_recents",
                    "open_notifications",
                    "open_quick_settings",
                    "dismiss_keyboard",
                ),
            ToolPolicyClass.SCREEN_READ to listOf("get_screen_state", "find_nodes", "wait_for_node", "wait_for_idle"),
            ToolPolicyClass.PRESS_BACK to listOf("press_back"),
            ToolPolicyClass.CLIPBOARD_WRITE to listOf("set_clipboard"),
            ToolPolicyClass.NODE_DETAILS_READ to listOf("get_node_details"),
            ToolPolicyClass.NODE_CLICK to listOf("click_node"),
            ToolPolicyClass.NODE_LONG_CLICK to listOf("long_click_node"),
            ToolPolicyClass.NODE_TAP to listOf("tap_node"),
            ToolPolicyClass.NODE_SCROLL to listOf("scroll_to_node"),
            ToolPolicyClass.POINT_GESTURE to listOf("tap", "long_press", "double_tap"),
            ToolPolicyClass.PINCH to listOf("pinch"),
            ToolPolicyClass.CUSTOM_GESTURE to listOf("custom_gesture"),
            ToolPolicyClass.SWIPE to listOf("swipe"),
            ToolPolicyClass.SCREEN_SCROLL to listOf("scroll"),
            ToolPolicyClass.TEXT_EDIT to
                listOf(
                    "type_append_text",
                    "type_insert_text",
                    "type_replace_text",
                    "type_clear_text",
                ),
            ToolPolicyClass.PRESS_KEY to listOf("press_key"),
            ToolPolicyClass.NOTIFICATION_LIST to listOf("notification_list"),
            ToolPolicyClass.NOTIFICATION_OPEN to listOf("notification_open"),
            ToolPolicyClass.NOTIFICATION_CONTROL to listOf("notification_dismiss", "notification_snooze"),
            ToolPolicyClass.NOTIFICATION_ACTION to listOf("notification_action"),
            ToolPolicyClass.NOTIFICATION_REPLY to listOf("notification_reply"),
            ToolPolicyClass.APP_LAUNCH to listOf("open_app", "close_app"),
            ToolPolicyClass.SEND_INTENT to listOf("send_intent"),
            ToolPolicyClass.OPEN_URI to listOf("open_uri"),
        )

    /** Un-prefixed tool name → policy class. */
    val classes: Map<String, ToolPolicyClass> =
        TOOLS_BY_CLASS.flatMap { (policyClass, names) -> names.map { it to policyClass } }.toMap()

    /** Count of listed names; equals `classes.size` only when no name is listed under two classes. */
    internal val listedNameCount: Int = TOOLS_BY_CLASS.values.sumOf { it.size }

    fun classOf(toolName: String): ToolPolicyClass? = classes[toolName]

    fun requireClass(toolName: String): ToolPolicyClass =
        checkNotNull(classes[toolName]) { "Tool '$toolName' has no entry in ToolPolicyCatalog" }
}
