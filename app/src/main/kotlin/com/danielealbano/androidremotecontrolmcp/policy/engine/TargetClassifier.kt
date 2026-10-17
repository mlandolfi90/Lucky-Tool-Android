package com.danielealbano.androidremotecontrolmcp.policy.engine

import com.danielealbano.androidremotecontrolmcp.policy.model.ApprovalProfile
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyException
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicySelector

internal data class TargetClassification(
    val actionType: PolicyActionType,
    val reason: DecisionReason,
)

/**
 * Classifies a tapped/clicked target in a profiled app: an action keyword → ACTION_BUTTON (beats exceptions);
 * identifiable with a label that has a letter → NAVIGATE; excepted by `notActionButtons` → NAVIGATE; anything
 * else (icon, canvas, WebView, no hit) → ACTION_BUTTON.
 */
internal object TargetClassifier {
    private val KEYWORD = TargetClassification(PolicyActionType.ACTION_BUTTON, DecisionReason.ACTION_KEYWORD)
    private val NAVIGATION = TargetClassification(PolicyActionType.NAVIGATE, DecisionReason.RULE)
    private val UNKNOWN = TargetClassification(PolicyActionType.ACTION_BUTTON, DecisionReason.UNKNOWN_TARGET)

    fun classify(
        subject: PolicySubject,
        target: PolicyTarget,
        profile: ApprovalProfile,
    ): TargetClassification {
        val label = target.nodeLabel
        return when {
            !subject.classifyTarget -> TargetClassification(subject.actionType, DecisionReason.RULE)
            label != null && ActionKeywords.matches(label) -> KEYWORD
            target.identifiable && label != null && PolicyLabels.words(label).isNotEmpty() -> NAVIGATION
            isExcepted(target.selector, profile.notActionButtons) -> NAVIGATION
            else -> UNKNOWN
        }
    }

    fun isExcepted(
        selector: PolicySelector?,
        exceptions: List<PolicyException>,
    ): Boolean = selector != null && exceptions.any { it.selector == selector }
}
