package com.danielealbano.androidremotecontrolmcp.policy.di

import com.danielealbano.androidremotecontrolmcp.policy.subject.IntentTargetResolver
import com.danielealbano.androidremotecontrolmcp.policy.subject.IntentTargetResolverImpl
import com.danielealbano.androidremotecontrolmcp.policy.subject.PolicyWindowSource
import com.danielealbano.androidremotecontrolmcp.policy.subject.PolicyWindowSourceImpl
import com.danielealbano.androidremotecontrolmcp.policy.subject.ToolSubjectResolver
import com.danielealbano.androidremotecontrolmcp.policy.subject.ToolSubjectResolverImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Subject resolution of the per-app approval profiles. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PolicySubjectModule {
    @Binds
    @Singleton
    abstract fun bindToolSubjectResolver(impl: ToolSubjectResolverImpl): ToolSubjectResolver

    @Binds
    @Singleton
    abstract fun bindPolicyWindowSource(impl: PolicyWindowSourceImpl): PolicyWindowSource

    @Binds
    @Singleton
    abstract fun bindIntentTargetResolver(impl: IntentTargetResolverImpl): IntentTargetResolver
}
