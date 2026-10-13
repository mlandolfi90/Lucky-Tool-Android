package com.danielealbano.androidremotecontrolmcp.policy

import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicyEngine
import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicyEngineImpl
import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicySubject
import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicyTarget
import com.danielealbano.androidremotecontrolmcp.policy.model.AmountTier
import com.danielealbano.androidremotecontrolmcp.policy.model.ApprovalProfile
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyException
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicySelector
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfileTemplate
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfileTemplates
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfilesSnapshot
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode
import com.danielealbano.androidremotecontrolmcp.policy.model.SelectorKind

/** Pure builders shared by the policy tests (engine, storage, subject resolution, gate, approvals). No mocks. */
object PolicyFixtures {
    const val OWN_PACKAGE = "com.danielealbano.androidremotecontrolmcp.gms.debug"
    const val BANK_PACKAGE = "com.example.bank"
    const val NOTES_PACKAGE = "com.example.notes"
    const val UNPROFILED_PACKAGE = "com.example.weather"
    const val BANK_MAX_AMOUNT = "10000"
    const val NOW_MS = 1_760_000_000_000L

    fun moneyProfile(
        packageName: String = BANK_PACKAGE,
        maxAmount: String? = BANK_MAX_AMOUNT,
        amountTiers: List<AmountTier> = emptyList(),
        notAmountFields: List<PolicySelector> = emptyList(),
        notActionButtons: List<PolicySelector> = emptyList(),
    ): ApprovalProfile =
        ProfileTemplates
            .newProfile(packageName, ProfileTemplate.MONEY_APP, NOW_MS)
            .copy(
                maxAmount = maxAmount,
                amountTiers = amountTiers,
                notAmountFields = notAmountFields.map { PolicyException(it, NOW_MS) },
                notActionButtons = notActionButtons.map { PolicyException(it, NOW_MS) },
            )

    fun commonProfile(
        packageName: String = NOTES_PACKAGE,
        maxAmount: String? = null,
        amountTiers: List<AmountTier> = emptyList(),
    ): ApprovalProfile =
        ProfileTemplates
            .newProfile(packageName, ProfileTemplate.COMMON_APP, NOW_MS)
            .copy(maxAmount = maxAmount, amountTiers = amountTiers)

    fun snapshotOf(
        vararg profiles: ApprovalProfile,
        unreadable: Set<String> = emptySet(),
    ): ProfilesSnapshot = ProfilesSnapshot(profiles.associateBy { it.packageName }, unreadable)

    fun target(
        packageName: String = BANK_PACKAGE,
        label: String? = null,
        selector: PolicySelector? = null,
        isPassword: Boolean = false,
        identifiable: Boolean = true,
    ): PolicyTarget = PolicyTarget(packageName, label, selector, isPassword, identifiable)

    fun subject(
        actionType: PolicyActionType,
        targets: List<PolicyTarget> = listOf(target()),
        amountText: String? = null,
        classifyTarget: Boolean = false,
    ): PolicySubject = PolicySubject(actionType, targets, amountText, classifyTarget)

    fun resourceId(id: String): PolicySelector = PolicySelector(SelectorKind.RESOURCE_ID, id)

    fun tier(
        upTo: String,
        mode: RuleMode,
    ): AmountTier = AmountTier(upTo, mode)

    fun engine(): PolicyEngine = PolicyEngineImpl(OWN_PACKAGE)
}
