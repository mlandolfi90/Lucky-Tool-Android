package com.danielealbano.androidremotecontrolmcp.integration

import com.danielealbano.androidremotecontrolmcp.data.repository.ApprovalProfileRepository
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogRepository
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures
import com.danielealbano.androidremotecontrolmcp.policy.approval.ActionApprovalCoordinator
import com.danielealbano.androidremotecontrolmcp.policy.approval.ActionApprovalCoordinatorImpl
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingAction
import com.danielealbano.androidremotecontrolmcp.policy.gate.GateTiming
import com.danielealbano.androidremotecontrolmcp.policy.gate.ToolPolicyGate
import com.danielealbano.androidremotecontrolmcp.policy.gate.ToolPolicyGateImpl
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalEventLoggerImpl
import com.danielealbano.androidremotecontrolmcp.policy.model.ApprovalProfile
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfilesSnapshot
import com.danielealbano.androidremotecontrolmcp.testutil.ScriptedToolSubjectResolver
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicLong

/**
 * The REAL profile gate wired into every integration test: real engine ([PolicyFixtures.engine], own package
 * [PolicyFixtures.OWN_PACKAGE]), real [ActionApprovalCoordinatorImpl], a fake repository over [profiles], scripted
 * subjects ([subjects]) and a controllable clock and NOTIFY wait.
 */
class PolicyTestRig private constructor(
    val profiles: MutableStateFlow<ProfilesSnapshot>,
    val subjects: ScriptedToolSubjectResolver,
    val coordinator: ActionApprovalCoordinator,
    val gate: ToolPolicyGate,
    private val clock: AtomicLong,
) {
    fun nowMs(): Long = clock.get()

    fun advanceClock(deltaMs: Long) {
        clock.addAndGet(deltaMs)
    }

    fun setProfiles(vararg approvalProfiles: ApprovalProfile) {
        profiles.value = PolicyFixtures.snapshotOf(*approvalProfiles)
    }

    /** Suspends until the coordinator holds a pending entry and returns the oldest one. */
    suspend fun awaitPending(): PendingAction = coordinator.observePending().first { it.isNotEmpty() }.first()

    companion object {
        const val DEFAULT_NOTIFY_WAIT_MS = 5_000L

        fun create(
            serverLog: ServerLogRepository,
            notifyWaitMs: Long,
        ): PolicyTestRig {
            val profiles = MutableStateFlow(ProfilesSnapshot.EMPTY)
            val repository = mockk<ApprovalProfileRepository>()
            every { repository.profiles } returns profiles
            coEvery { repository.current() } answers { profiles.value }
            val subjects = ScriptedToolSubjectResolver()
            val coordinator = ActionApprovalCoordinatorImpl()
            val clock = AtomicLong(PolicyFixtures.NOW_MS)
            val gate =
                ToolPolicyGateImpl(
                    subjects,
                    PolicyFixtures.engine(),
                    coordinator,
                    repository,
                    ApprovalEventLoggerImpl(serverLog),
                    GateTiming(notifyWaitMs) { clock.get() },
                )
            return PolicyTestRig(profiles, subjects, coordinator, gate, clock)
        }
    }
}
