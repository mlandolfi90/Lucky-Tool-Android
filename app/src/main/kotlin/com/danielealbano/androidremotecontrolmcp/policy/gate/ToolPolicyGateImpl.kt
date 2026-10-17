package com.danielealbano.androidremotecontrolmcp.policy.gate

import com.danielealbano.androidremotecontrolmcp.data.repository.ApprovalProfileRepository
import com.danielealbano.androidremotecontrolmcp.policy.approval.ActionApprovalCoordinator
import com.danielealbano.androidremotecontrolmcp.policy.approval.ApprovalScreenVisibility
import com.danielealbano.androidremotecontrolmcp.policy.approval.ApprovalScreenVisibilityImpl
import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicyEngine
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalEventLogger
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode
import com.danielealbano.androidremotecontrolmcp.policy.subject.ResolverKind
import com.danielealbano.androidremotecontrolmcp.policy.subject.ToolPolicyCatalog
import com.danielealbano.androidremotecontrolmcp.policy.subject.ToolPolicyClass
import com.danielealbano.androidremotecontrolmcp.policy.subject.ToolSubjectResolver
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The profile gate; decides before any tool mutex. While a profile exists (or the store is unreadable), a call of a
 * class that is not read-only is resolved and evaluated ONCE under [mutationMutex] and, when authorized, executes
 * before the lock is released. A NOTIFY wait releases the lock; once the owner approves, the call is re-resolved and
 * re-evaluated under the lock and executes only if the authorization still holds for the same fingerprint, or the
 * fresh decision allows it alone. With zero profiles, and for read-only classes, no lock is taken.
 */
@Singleton
class ToolPolicyGateImpl internal constructor(
    subjectResolver: ToolSubjectResolver,
    engine: PolicyEngine,
    coordinator: ActionApprovalCoordinator,
    private val profileRepository: ApprovalProfileRepository,
    events: ApprovalEventLogger,
    private val timing: GateTiming,
    screen: ApprovalScreenVisibility = ApprovalScreenVisibilityImpl(),
) : ToolPolicyGate {
    @Inject
    constructor(
        subjectResolver: ToolSubjectResolver,
        engine: PolicyEngine,
        coordinator: ActionApprovalCoordinator,
        profileRepository: ApprovalProfileRepository,
        events: ApprovalEventLogger,
        screen: ApprovalScreenVisibility,
    ) : this(subjectResolver, engine, coordinator, profileRepository, events, GateTiming.PRODUCTION, screen)

    private val assessor = GateAssessor(subjectResolver, engine, profileRepository)
    private val authorizer = GateAuthorizer(coordinator, GateOutcomes(events, timing.nowMs), timing.nowMs, screen)
    private val mutationMutex = Mutex()

    override suspend fun guard(
        toolName: String,
        arguments: JsonObject?,
        execute: suspend () -> CallToolResult,
    ): CallToolResult {
        val policyClass = ToolPolicyCatalog.requireClass(toolName)
        // Not app-scoped and exempt-exit tools never read profiles: a broken store cannot block them.
        if (policyClass.kind == ResolverKind.NONE) {
            return execute()
        }
        val call = GateCall(toolName, arguments, timing.nowMs())
        val serialized = policyClass !in READ_ONLY_CLASSES && ProfilesView.load(profileRepository).anyProfile
        return if (serialized) guardSerialized(call, execute) else guardConcurrently(call, execute)
    }

    /** One resolution and evaluation under the lock; an authorized call executes before the lock is released. */
    private suspend fun guardSerialized(
        call: GateCall,
        execute: suspend () -> CallToolResult,
    ): CallToolResult {
        var awaiting: Authorization.AwaitOwner? = null
        val result =
            mutationMutex.withLock {
                when (val authorization = authorizer.authorize(call, assessor.assess(call))) {
                    is Authorization.AwaitOwner -> {
                        awaiting = authorization
                        null
                    }

                    is Verdict.Halt -> {
                        authorization.result
                    }

                    is Verdict.Proceed -> {
                        execute()
                    }
                }
            }
        return result ?: afterOwner(call, checkNotNull(awaiting), execute)
    }

    /** Zero profiles when the call arrived, or a read-only class: decided without the lock. */
    private suspend fun guardConcurrently(
        call: GateCall,
        execute: suspend () -> CallToolResult,
    ): CallToolResult {
        val first = assessor.assess(call)
        return when (val authorization = authorizer.authorize(call, first)) {
            is Authorization.AwaitOwner -> afterOwner(call, authorization, execute)
            is Verdict.Halt -> authorization.result
            is Verdict.Proceed -> runAuthorized(call, first, authorization.mode, execute)
        }
    }

    /** NOTIFY: waits for the owner with the lock released, then executes through [runAuthorized]. */
    private suspend fun afterOwner(
        call: GateCall,
        awaiting: Authorization.AwaitOwner,
        execute: suspend () -> CallToolResult,
    ): CallToolResult =
        when (val verdict = authorizer.awaitOwner(call, awaiting, timing.notifyWaitMs)) {
            is Verdict.Halt -> verdict.result
            is Verdict.Proceed -> runAuthorized(call, awaiting.assessment, verdict.mode, execute, ownerGrant = true)
        }

    /**
     * A mutating call while profiles apply re-checks under the lock before executing; any other call executes. With
     * [ownerGrant] the owner's single-use grant is consumed only right before executing, so a re-check that fails
     * (the target app is not on screen yet) leaves it for the repeat.
     */
    private suspend fun runAuthorized(
        call: GateCall,
        first: Assessment,
        authorizedMode: RuleMode,
        execute: suspend () -> CallToolResult,
        ownerGrant: Boolean = false,
    ): CallToolResult =
        if (first is Assessment.Evaluated && first.mutatesUnderProfiles) {
            mutationMutex.withLock { recheckAndExecute(call, first, authorizedMode, execute, ownerGrant) }
        } else if (ownerGrant && first is Assessment.Evaluated) {
            when (val verdict = authorizer.consumeOwnerGrant(call, first)) {
                is Verdict.Proceed -> execute()
                is Verdict.Halt -> verdict.result
            }
        } else {
            execute()
        }

    private suspend fun recheckAndExecute(
        call: GateCall,
        first: Assessment.Evaluated,
        authorizedMode: RuleMode,
        execute: suspend () -> CallToolResult,
        ownerGrant: Boolean,
    ): CallToolResult {
        val second = assessor.assess(call)
        val carried =
            second is Assessment.Evaluated &&
                second.fingerprint == first.fingerprint &&
                !second.decision.mode.isStricterThan(authorizedMode)
        val verdict =
            if (carried) {
                if (ownerGrant) authorizer.consumeOwnerGrant(call, first) else Verdict.Proceed(authorizedMode)
            } else {
                when (val again = authorizer.authorize(call, second)) {
                    is Authorization.AwaitOwner -> afterImmediateOwnerCheck(call, again)
                    is Verdict -> again
                }
            }
        return when (verdict) {
            is Verdict.Proceed -> execute()
            is Verdict.Halt -> verdict.result
        }
    }

    /** A changed call that joined an entry: reads the owner's answer without waiting and consumes an approval. */
    private suspend fun afterImmediateOwnerCheck(
        call: GateCall,
        awaiting: Authorization.AwaitOwner,
    ): Verdict =
        when (val verdict = authorizer.awaitOwner(call, awaiting, NO_WAIT_MS)) {
            is Verdict.Halt -> verdict
            is Verdict.Proceed -> authorizer.consumeOwnerGrant(call, awaiting.assessment)
        }

    private companion object {
        const val NO_WAIT_MS = 0L

        /** Classes whose subject is always READ (or not app-scoped): decided without the lock. */
        val READ_ONLY_CLASSES =
            setOf(ToolPolicyClass.SCREEN_READ, ToolPolicyClass.NODE_DETAILS_READ, ToolPolicyClass.NOTIFICATION_LIST)
    }
}
