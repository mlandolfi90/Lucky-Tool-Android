package com.danielealbano.androidremotecontrolmcp.policy.engine

import com.danielealbano.androidremotecontrolmcp.policy.model.ApprovalProfile
import com.danielealbano.androidremotecontrolmcp.policy.model.ApprovalProfileValidator
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfilesSnapshot
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PolicyEngine] over a [ProfilesSnapshot]. Per target: own package + non-READ → FORBIDDEN (always, even with
 * zero profiles); unreadable or invalid stored profile → non-READ FORBIDDEN; no profile → RUN; otherwise the
 * classified rule combined with the amount check, strictest wins. Across targets the strictest decision wins.
 * A subject without targets is FORBIDDEN for non-READ actions whenever any profile exists (cannot attribute it).
 */
@Singleton
class PolicyEngineImpl
    @Inject
    constructor(
        @param:OwnPackageName private val ownPackageName: String,
    ) : PolicyEngine {
        override fun evaluate(
            subject: PolicySubject,
            snapshot: ProfilesSnapshot,
        ): PolicyDecision {
            if (subject.targets.isEmpty()) return untargetedDecision(subject, snapshot)
            return subject.targets
                .map { target -> evaluateTarget(subject, target, snapshot) }
                .reduce { current, candidate -> stricterOf(current, candidate) }
        }

        private fun untargetedDecision(
            subject: PolicySubject,
            snapshot: ProfilesSnapshot,
        ): PolicyDecision {
            val failClosed = subject.actionType != PolicyActionType.READ && snapshot.hasAnyProfile
            return if (failClosed) {
                PolicyDecision(RuleMode.FORBIDDEN, DecisionReason.UNKNOWN_TARGET, null, subject.actionType)
            } else {
                PolicyDecision(RuleMode.RUN, DecisionReason.NO_PROFILE, null, subject.actionType)
            }
        }

        private fun evaluateTarget(
            subject: PolicySubject,
            target: PolicyTarget,
            snapshot: ProfilesSnapshot,
        ): PolicyDecision {
            val packageName = target.packageName
            val profile = snapshot.profiles[packageName]
            val isRead = subject.actionType == PolicyActionType.READ
            return when {
                packageName == ownPackageName && !isRead -> {
                    PolicyDecision(RuleMode.FORBIDDEN, DecisionReason.OWN_PACKAGE, packageName, subject.actionType)
                }

                isUnreadable(packageName, profile, snapshot) -> {
                    PolicyDecision(
                        if (isRead) RuleMode.RUN else RuleMode.FORBIDDEN,
                        DecisionReason.PROFILE_UNREADABLE,
                        packageName,
                        subject.actionType,
                    )
                }

                profile == null -> {
                    PolicyDecision(RuleMode.RUN, DecisionReason.NO_PROFILE, packageName, subject.actionType)
                }

                else -> {
                    profiledDecision(subject, target, profile)
                }
            }
        }

        private fun isUnreadable(
            packageName: String,
            profile: ApprovalProfile?,
            snapshot: ProfilesSnapshot,
        ): Boolean =
            packageName in snapshot.unreadable ||
                (profile != null && ApprovalProfileValidator.validate(profile).isNotEmpty())

        private fun profiledDecision(
            subject: PolicySubject,
            target: PolicyTarget,
            profile: ApprovalProfile,
        ): PolicyDecision {
            val classification = TargetClassifier.classify(subject, target, profile)
            val ruleMode = profile.rules[classification.actionType] ?: RuleMode.FORBIDDEN
            val base = PolicyDecision(ruleMode, classification.reason, target.packageName, classification.actionType)
            val amount = AmountRule.evaluate(subject.amountText, target, profile)
            return if (amount != null && amount.mode.isStricterThan(base.mode)) {
                base.copy(mode = amount.mode, reason = amount.reason)
            } else {
                base
            }
        }

        private fun stricterOf(
            current: PolicyDecision,
            candidate: PolicyDecision,
        ): PolicyDecision {
            val replacesUnprofiled =
                candidate.mode == current.mode &&
                    current.reason == DecisionReason.NO_PROFILE &&
                    candidate.reason != DecisionReason.NO_PROFILE
            return if (candidate.mode.isStricterThan(current.mode) || replacesUnprofiled) candidate else current
        }
    }
