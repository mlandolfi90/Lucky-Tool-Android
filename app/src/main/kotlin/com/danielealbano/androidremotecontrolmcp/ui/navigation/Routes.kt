package com.danielealbano.androidremotecontrolmcp.ui.navigation

import com.danielealbano.androidremotecontrolmcp.policy.model.ProfileTemplate

sealed class TopLevelRoute(
    val route: String,
) {
    data object Server : TopLevelRoute("server")

    data object Settings : TopLevelRoute("settings")

    data object About : TopLevelRoute("about")
}

sealed class SettingsRoute(
    val route: String,
) {
    data object Index : SettingsRoute("settings/index")

    data object General : SettingsRoute("settings/general")

    data object Access : SettingsRoute("settings/access")

    data object OAuthClients : SettingsRoute("settings/access/clients")

    data object Security : SettingsRoute("settings/security")

    data object Privacy : SettingsRoute("settings/privacy")

    data object Tunnel : SettingsRoute("settings/tunnel")

    data object McpTools : SettingsRoute("settings/mcp_tools")

    data object Permissions : SettingsRoute("settings/permissions")

    data object Storage : SettingsRoute("settings/storage")

    data object ChannelSettings : SettingsRoute("settings/channel")

    data object NotificationFilter : SettingsRoute("settings/channel/notification_filter")

    data object WifiMonitor : SettingsRoute("settings/channel/wifi_monitor")

    data object ApprovalProfiles : SettingsRoute("settings/approval_profiles")

    data object ApprovalProfileAdd : SettingsRoute("settings/approval_profiles/add")

    data object ApprovalProfileEdit : SettingsRoute("settings/approval_profiles/edit/{packageName}/{template}") {
        const val ARG_PACKAGE_NAME = "packageName"
        const val ARG_TEMPLATE = "template"
        private const val EXISTING = "existing"

        /** [template] null opens the stored profile; non-null starts a new unsaved one. */
        fun build(
            packageName: String,
            template: ProfileTemplate?,
        ): String = "settings/approval_profiles/edit/$packageName/${template?.name ?: EXISTING}"
    }
}

sealed class ServerRoute(
    val route: String,
) {
    data object Index : ServerRoute("server/index")

    data object Logs : ServerRoute("server/logs")
}
