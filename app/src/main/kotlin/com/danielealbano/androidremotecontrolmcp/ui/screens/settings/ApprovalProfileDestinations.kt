package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfileTemplate
import com.danielealbano.androidremotecontrolmcp.ui.navigation.SettingsRoute

/** Registers the approval-profile list, add and editor destinations of the settings graph. */
fun NavGraphBuilder.approvalProfileDestinations(navController: NavHostController) {
    composable(SettingsRoute.ApprovalProfiles.route) {
        ApprovalProfilesScreen(
            onBack = { navController.popBackStack() },
            onAdd = { navController.navigate(SettingsRoute.ApprovalProfileAdd.route) },
            onEdit = { packageName ->
                navController.navigate(SettingsRoute.ApprovalProfileEdit.build(packageName, template = null))
            },
        )
    }
    composable(SettingsRoute.ApprovalProfileAdd.route) {
        ApprovalProfileAddScreen(
            onBack = { navController.popBackStack() },
            onContinue = { packageName, template ->
                navController.popBackStack()
                navController.navigate(SettingsRoute.ApprovalProfileEdit.build(packageName, template))
            },
        )
    }
    composable(SettingsRoute.ApprovalProfileEdit.route) { backStackEntry ->
        val arguments = backStackEntry.arguments
        val packageName = arguments?.getString(SettingsRoute.ApprovalProfileEdit.ARG_PACKAGE_NAME).orEmpty()
        val templateArg = arguments?.getString(SettingsRoute.ApprovalProfileEdit.ARG_TEMPLATE)
        ApprovalProfileEditorScreen(
            packageName = packageName,
            template = ProfileTemplate.entries.firstOrNull { it.name == templateArg },
            onBack = { navController.popBackStack() },
        )
    }
}
