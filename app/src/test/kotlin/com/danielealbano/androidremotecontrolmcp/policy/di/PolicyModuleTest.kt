package com.danielealbano.androidremotecontrolmcp.policy.di

import com.danielealbano.androidremotecontrolmcp.BuildConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PolicyModuleTest {
    @Test
    fun `own package name is the build applicationId`() {
        assertEquals(BuildConfig.APPLICATION_ID, PolicyModule.provideOwnPackageName())
    }

    @Test
    fun `own package name matches the installed package id`() {
        val expected =
            if (BuildConfig.BUILD_TYPE == "debug") {
                "com.danielealbano.androidremotecontrolmcp.${BuildConfig.FLAVOR}.debug"
            } else {
                "com.danielealbano.androidremotecontrolmcp"
            }

        assertEquals(expected, PolicyModule.provideOwnPackageName())
    }
}
