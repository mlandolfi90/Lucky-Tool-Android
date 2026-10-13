package com.danielealbano.androidremotecontrolmcp.policy.subject

import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicySubject
import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicyTarget
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData

/**
 * How touched nodes become a subject: action type, and whether each target carries its label and selector
 * ([classified]); [classified] is also the subject's `classifyTarget`.
 */
internal enum class TouchStyle(
    val actionType: PolicyActionType,
    val classified: Boolean,
) {
    CLASSIFIED_ACTION(PolicyActionType.ACTION_BUTTON, true),
    FIXED_ACTION(PolicyActionType.ACTION_BUTTON, false),
    NAVIGATION(PolicyActionType.NAVIGATE, false),
    READING(PolicyActionType.READ, false),
}

/** Attribution rules shared by every resolver. Fail-closed: unattributable = FORBIDDEN while ≥1 profile exists. */
internal object SubjectSupport {
    const val WINDOW_TYPE_APPLICATION = "APPLICATION"
    const val WINDOW_TYPE_INPUT_METHOD = "INPUT_METHOD"
    const val WINDOW_TYPE_ACCESSIBILITY_OVERLAY = "ACCESSIBILITY_OVERLAY"

    fun unattributed(
        actionType: PolicyActionType,
        profiledPackages: Set<String>,
        cause: BlockCause = BlockCause.TARGET_UNRESOLVED,
    ): SubjectResolution =
        if (profiledPackages.isEmpty()) {
            SubjectResolution.Unattributed
        } else {
            SubjectResolution.Blocked(null, actionType, cause)
        }

    /** Unclassified subject (`classifyTarget = false`). */
    fun resolved(
        actionType: PolicyActionType,
        targets: List<PolicyTarget>,
        profiledPackages: Set<String>,
        amountText: String? = null,
    ): SubjectResolution =
        if (targets.isEmpty()) {
            unattributed(actionType, profiledPackages)
        } else {
            SubjectResolution.Resolved(
                PolicySubject(actionType = actionType, targets = targets.distinct(), amountText = amountText),
            )
        }

    fun packageOf(window: WindowData): String? = window.packageName?.takeIf { it.isNotBlank() }

    /** Input-focused APPLICATION window's package; else the top-layer APPLICATION window's package. */
    fun focusedAppPackage(windows: List<WindowData>): String? {
        val apps = windows.filter { it.windowType == WINDOW_TYPE_APPLICATION }
        return (apps.firstOrNull { it.focused } ?: apps.maxByOrNull { it.layer })?.let(::packageOf)
    }

    fun focusedAppSubject(
        result: MultiWindowResult,
        actionType: PolicyActionType,
        profiledPackages: Set<String>,
    ): SubjectResolution {
        val packageName = if (result.degraded) null else focusedAppPackage(result.windows)
        return if (packageName == null) {
            unattributed(actionType, profiledPackages)
        } else {
            resolved(actionType, listOf(PolicyTargets.packageOnly(packageName)), profiledPackages)
        }
    }

    /** READ of every on-screen window: one package-only target per window, strictest READ rule wins (R10). */
    fun allWindowsRead(
        result: MultiWindowResult,
        profiledPackages: Set<String>,
    ): SubjectResolution {
        val packages = result.windows.map(::packageOf)
        return if (result.degraded || packages.any { it == null }) {
            unattributed(PolicyActionType.READ, profiledPackages)
        } else {
            resolved(PolicyActionType.READ, packages.filterNotNull().map(PolicyTargets::packageOnly), profiledPackages)
        }
    }

    /** First window (tool order, same as `ActionExecutor`) whose tree holds [nodeId]. */
    fun locate(
        elementFinder: ElementFinder,
        windows: List<WindowData>,
        nodeId: String,
    ): NodeLocation? =
        windows.firstNotNullOfOrNull { window ->
            elementFinder.findNodeById(window.tree, nodeId)?.let { NodeLocation(window, it) }
        }

    /**
     * Subject of a touch on [touched]: IME guard first (non-READ), then attribution, then one target per node.
     * The subject's `classifyTarget` is [TouchStyle.classified]. [ownPackageName] = the injected `@OwnPackageName`.
     */
    fun touchSubject(
        windows: List<WindowData>,
        touched: List<NodeLocation>,
        style: TouchStyle,
        profiledPackages: Set<String>,
        ownPackageName: String,
    ): SubjectResolution {
        val blocked =
            if (style.actionType == PolicyActionType.READ) {
                null
            } else {
                imeBlock(windows, touched.map { it.window }, style.actionType, profiledPackages, ownPackageName)
            }
        val unattributable = touched.isEmpty() || touched.any { packageOf(it.window) == null }
        return when {
            blocked != null -> {
                blocked
            }

            unattributable -> {
                unattributed(style.actionType, profiledPackages)
            }

            else -> {
                SubjectResolution.Resolved(
                    PolicySubject(
                        actionType = style.actionType,
                        targets = touched.map { targetOf(it, style) }.distinct(),
                        amountText = null,
                        classifyTarget = style.classified,
                    ),
                )
            }
        }
    }

    /** Keyboard touched while a profiled app (or this app) holds input focus; unknown focus counts when profiled. */
    private fun imeBlock(
        windows: List<WindowData>,
        touched: List<WindowData>,
        actionType: PolicyActionType,
        profiledPackages: Set<String>,
        ownPackageName: String,
    ): SubjectResolution? {
        val focused = focusedAppPackage(windows)
        val keyboardTouched = touched.any { it.windowType == WINDOW_TYPE_INPUT_METHOD }
        val guarded =
            if (focused == null) {
                profiledPackages.isNotEmpty()
            } else {
                focused in profiledPackages || focused == ownPackageName
            }
        return if (keyboardTouched && guarded) {
            SubjectResolution.Blocked(focused, actionType, BlockCause.IME_INPUT_IN_PROFILED_APP)
        } else {
            null
        }
    }

    private fun targetOf(
        location: NodeLocation,
        style: TouchStyle,
    ): PolicyTarget {
        val packageName = checkNotNull(packageOf(location.window))
        return if (style.classified) {
            PolicyTargets.interactive(packageName, location.node)
        } else {
            PolicyTargets.packageOnly(packageName)
        }
    }
}
