package com.danielealbano.androidremotecontrolmcp.policy.model

/** Values a new profile starts with; everything stays editable afterwards. */
object ProfileTemplates {
    fun rulesFor(template: ProfileTemplate): Map<PolicyActionType, RuleMode> =
        when (template) {
            ProfileTemplate.MONEY_APP -> {
                mapOf(
                    PolicyActionType.READ to RuleMode.RUN,
                    PolicyActionType.NAVIGATE to RuleMode.RUN,
                    PolicyActionType.TYPE to RuleMode.ASK_IN_CHAT,
                    PolicyActionType.ACTION_BUTTON to RuleMode.NOTIFY,
                )
            }

            ProfileTemplate.COMMON_APP -> {
                mapOf(
                    PolicyActionType.READ to RuleMode.RUN,
                    PolicyActionType.NAVIGATE to RuleMode.RUN,
                    PolicyActionType.TYPE to RuleMode.RUN,
                    PolicyActionType.ACTION_BUTTON to RuleMode.ASK_IN_CHAT,
                )
            }
        }

    /**
     * A new profile from [template]. A [ProfileTemplate.MONEY_APP] profile starts without `maxAmount`, so it does
     * not validate (`MAX_AMOUNT_REQUIRED`) until the owner enters one.
     */
    fun newProfile(
        packageName: String,
        template: ProfileTemplate,
        nowMs: Long,
    ): ApprovalProfile =
        ApprovalProfile(
            packageName = packageName,
            template = template,
            rules = rulesFor(template),
            maxAmount = null,
            amountTiers = emptyList(),
            notAmountFields = emptyList(),
            notActionButtons = emptyList(),
            updatedAtMs = nowMs,
        )
}
