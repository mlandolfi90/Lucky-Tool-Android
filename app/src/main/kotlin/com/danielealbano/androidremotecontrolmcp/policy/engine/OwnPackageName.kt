package com.danielealbano.androidremotecontrolmcp.policy.engine

import javax.inject.Qualifier

/** Qualifier for this app's own `applicationId` (`BuildConfig.APPLICATION_ID`), provided by `PolicyModule`. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OwnPackageName
