package com.danielealbano.androidremotecontrolmcp.policy.gate

import com.danielealbano.androidremotecontrolmcp.data.repository.ApprovalProfileRepository
import com.danielealbano.androidremotecontrolmcp.data.repository.ProfileStoreUnreadableException
import com.danielealbano.androidremotecontrolmcp.policy.approval.CallFingerprint
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingAction
import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicyDecision
import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicyEngine
import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicySubject
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfilesSnapshot
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectResolution
import com.danielealbano.androidremotecontrolmcp.policy.subject.ToolSubjectResolver
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.JsonObject

/** One tool invocation as the gate sees it; [startMs] feeds the APPROVAL log duration. */
internal class GateCall(
    val toolName: String,
    val arguments: JsonObject?,
    val startMs: Long,
)

/** What one resolution + evaluation of a call produced. */
internal sealed interface Assessment {
    /** Nothing to check: `Unattributed` (no profile exists), or not app-scoped / exempt exit. */
    data object Unchecked : Assessment

    /** FORBIDDEN during subject resolution, before any rule. */
    class Blocked(
        val resolution: SubjectResolution.Blocked,
    ) : Assessment

    /** The engine's decision for a resolved subject, its approval fingerprint, and whether profiles apply. */
    class Evaluated(
        val subject: PolicySubject,
        val decision: PolicyDecision,
        val fingerprint: String,
        val anyProfile: Boolean,
    ) : Assessment {
        /** A mutating call while profiles apply: it executes only under the gate's mutation lock. */
        val mutatesUnderProfiles: Boolean
            get() = anyProfile && subject.actionType != PolicyActionType.READ
    }
}

/** What the gate does with one [Assessment]: a final [Verdict], or a NOTIFY entry to wait for. */
internal sealed interface Authorization {
    /** NOTIFY with no usable grant: the owner's answer on [pending] is awaited, always outside the mutation lock. */
    class AwaitOwner(
        val assessment: Assessment.Evaluated,
        val pending: PendingAction,
    ) : Authorization
}

/** A final authorization: execute in [Proceed.mode], or end the call with [Halt.result]. */
internal sealed interface Verdict : Authorization {
    class Proceed(
        val mode: RuleMode,
    ) : Verdict

    class Halt(
        val result: CallToolResult,
    ) : Verdict
}

/**
 * The profiles one gate pass decides with. A store that cannot be read at all (`current()` throws
 * [ProfileStoreUnreadableException]) is fail-closed: every package counts as profiled and unreadable until a later
 * read succeeds.
 */
internal class ProfilesView private constructor(
    private val snapshot: ProfilesSnapshot?,
) {
    /** Packages the subject resolver treats as profiled; exactly [UNREADABLE_STORE_PACKAGES] while unreadable. */
    val profiledPackages: Set<String> =
        snapshot?.let { it.profiles.keys + it.unreadable } ?: UNREADABLE_STORE_PACKAGES

    /** True when at least one profile is stored, or when the store is unreadable. */
    val anyProfile: Boolean = snapshot?.hasAnyProfile ?: true

    /** The snapshot the engine evaluates [subject] against. */
    fun snapshotFor(subject: PolicySubject): ProfilesSnapshot =
        snapshot
            ?: ProfilesSnapshot(
                profiles = emptyMap(),
                unreadable = subject.targets.map { it.packageName }.toSet() + UNREADABLE_STORE_PACKAGES,
            )

    companion object {
        /**
         * Fail-closed marker for an unreadable store. Not a valid package name ('<' is not allowed), so it matches no
         * app; it only marks "a profile may exist".
         */
        internal const val UNREADABLE_STORE_MARKER = "<approval-profiles-unreadable>"

        private val UNREADABLE_STORE_PACKAGES = setOf(UNREADABLE_STORE_MARKER)

        suspend fun load(repository: ApprovalProfileRepository): ProfilesView =
            try {
                ProfilesView(repository.current())
            } catch (_: ProfileStoreUnreadableException) {
                ProfilesView(null)
            }
    }
}

/** Resolves the subject of a call and evaluates it against the current profiles. */
internal class GateAssessor(
    private val subjectResolver: ToolSubjectResolver,
    private val engine: PolicyEngine,
    private val profileRepository: ApprovalProfileRepository,
) {
    suspend fun assess(call: GateCall): Assessment {
        val view = ProfilesView.load(profileRepository)
        val resolution = subjectResolver.resolve(call.toolName, call.arguments, view.profiledPackages)
        return when (resolution) {
            SubjectResolution.NotAppScoped, SubjectResolution.ExemptExit, SubjectResolution.Unattributed -> {
                Assessment.Unchecked
            }

            is SubjectResolution.Blocked -> {
                Assessment.Blocked(resolution)
            }

            is SubjectResolution.Resolved -> {
                evaluate(call, resolution.subject, view)
            }
        }
    }

    private fun evaluate(
        call: GateCall,
        subject: PolicySubject,
        view: ProfilesView,
    ): Assessment.Evaluated {
        val decision = engine.evaluate(subject, view.snapshotFor(subject))
        val selector = subject.targets.firstOrNull { it.packageName == decision.packageName }?.selector
        return Assessment.Evaluated(
            subject = subject,
            decision = decision,
            fingerprint = CallFingerprint.of(call.toolName, call.arguments, decision.packageName, selector),
            anyProfile = view.anyProfile,
        )
    }
}
