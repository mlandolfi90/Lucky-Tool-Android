package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import com.danielealbano.androidremotecontrolmcp.policy.model.ApprovalProfileValidator
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfileTemplate
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfileTemplates
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfileValidationError
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfilesSnapshot
import java.util.Locale

/** One row of the profile list; [template] is null for an [unreadable] (fail-closed) profile. */
data class ProfileListItem(
    val packageName: String,
    val appName: String?,
    val template: ProfileTemplate?,
    val unreadable: Boolean,
)

/** A launcher app that can still receive a profile. */
data class PickerApp(
    val packageName: String,
    val appName: String,
)

/** Why a manually typed package name cannot get a new profile. */
enum class ManualPackageError { EMPTY, INVALID_FORMAT, OWN_PACKAGE, ALREADY_PROFILED }

/** State of the profile list screen. */
data class ProfilesListUiState(
    val items: List<ProfileListItem> = emptyList(),
    val storeUnreadable: Boolean = false,
    val deleteFailed: Boolean = false,
)

/** Readable and unreadable profiles, named from [apps] (`packageName to appName`), sorted by display name. */
internal fun profileListItems(
    snapshot: ProfilesSnapshot,
    apps: List<Pair<String, String>>,
): List<ProfileListItem> {
    val names = apps.toMap()
    val readable =
        snapshot.profiles.values.map {
            ProfileListItem(it.packageName, names[it.packageName], it.template, unreadable = false)
        }
    val broken =
        (snapshot.unreadable - snapshot.profiles.keys).map {
            ProfileListItem(it, names[it], template = null, unreadable = true)
        }
    return (readable + broken).sortedWith(
        compareBy<ProfileListItem>({ (it.appName ?: it.packageName).lowercase(Locale.ROOT) }, { it.packageName }),
    )
}

/** Launcher apps without a stored profile (readable or not), never this app itself. */
internal fun toPickerApps(
    snapshot: ProfilesSnapshot,
    apps: List<Pair<String, String>>,
    ownPackageName: String,
): List<PickerApp> =
    apps
        .filter { (packageName, _) ->
            packageName != ownPackageName &&
                packageName !in snapshot.profiles &&
                packageName !in snapshot.unreadable
        }.map { (packageName, appName) -> PickerApp(packageName, appName) }

/** [packageName] must already be trimmed. The format rule is the validator's, not a second one. */
internal fun manualPackageError(
    packageName: String,
    snapshot: ProfilesSnapshot,
    ownPackageName: String,
): ManualPackageError? =
    when {
        packageName.isEmpty() -> ManualPackageError.EMPTY
        packageName == ownPackageName -> ManualPackageError.OWN_PACKAGE
        hasInvalidPackageFormat(packageName) -> ManualPackageError.INVALID_FORMAT
        packageName in snapshot.profiles || packageName in snapshot.unreadable -> ManualPackageError.ALREADY_PROFILED
        else -> null
    }

private fun hasInvalidPackageFormat(packageName: String): Boolean =
    ProfileValidationError.PACKAGE_NAME_INVALID in
        ApprovalProfileValidator.validate(ProfileTemplates.newProfile(packageName, ProfileTemplate.COMMON_APP, 0L))
