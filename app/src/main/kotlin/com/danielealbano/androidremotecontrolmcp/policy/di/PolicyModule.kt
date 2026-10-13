package com.danielealbano.androidremotecontrolmcp.policy.di

import com.danielealbano.androidremotecontrolmcp.BuildConfig
import com.danielealbano.androidremotecontrolmcp.policy.engine.OwnPackageName
import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicyEngine
import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicyEngineImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Hilt bindings of the per-app approval policy core (plan 70): the rule engine and this app's own package. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PolicyModule {
    /** Binds the pure, stateless rule engine. */
    @Binds
    @Singleton
    abstract fun bindPolicyEngine(impl: PolicyEngineImpl): PolicyEngine

    companion object {
        /** This app's own `applicationId` (debug builds include the flavor suffix) for the own-package guard. */
        @Provides
        @OwnPackageName
        fun provideOwnPackageName(): String = BuildConfig.APPLICATION_ID
    }
}
