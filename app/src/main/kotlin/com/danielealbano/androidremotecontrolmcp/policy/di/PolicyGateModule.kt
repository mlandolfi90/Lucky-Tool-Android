package com.danielealbano.androidremotecontrolmcp.policy.di

import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingReevaluator
import com.danielealbano.androidremotecontrolmcp.policy.gate.PendingReevaluatorImpl
import com.danielealbano.androidremotecontrolmcp.policy.gate.ToolPolicyGate
import com.danielealbano.androidremotecontrolmcp.policy.gate.ToolPolicyGateImpl
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalEventLogger
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalEventLoggerImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Tool-call gate, pending-approval re-evaluation and APPROVAL logging of the per-app approval profiles. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PolicyGateModule {
    @Binds
    @Singleton
    abstract fun bindToolPolicyGate(impl: ToolPolicyGateImpl): ToolPolicyGate

    @Binds
    @Singleton
    abstract fun bindPendingReevaluator(impl: PendingReevaluatorImpl): PendingReevaluator

    @Binds
    @Singleton
    abstract fun bindApprovalEventLogger(impl: ApprovalEventLoggerImpl): ApprovalEventLogger
}
