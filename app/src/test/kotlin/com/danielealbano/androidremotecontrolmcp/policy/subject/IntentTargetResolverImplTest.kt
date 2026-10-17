package com.danielealbano.androidremotecontrolmcp.policy.subject

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class IntentTargetResolverImplTest {
    private val packageManager = mockk<PackageManager>()
    private val context = mockk<Context>()
    private val resolver = IntentTargetResolverImpl(context)

    @BeforeEach
    fun setUp() {
        every { context.packageManager } returns packageManager
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk(relaxed = true)
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().setAction(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().setData(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().setType(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().setDataAndType(any(), any()) } answers { self as Intent }
    }

    @AfterEach
    fun tearDown() {
        unmockkConstructor(Intent::class)
        unmockkStatic(Uri::class)
    }

    private fun activity(packageName: String): ResolveInfo {
        val info = ActivityInfo()
        info.packageName = packageName
        val resolveInfo = mockk<ResolveInfo>()
        resolveInfo.activityInfo = info
        return resolveInfo
    }

    private fun service(packageName: String): ResolveInfo {
        val info = ServiceInfo()
        info.packageName = packageName
        val resolveInfo = mockk<ResolveInfo>()
        resolveInfo.serviceInfo = info
        return resolveInfo
    }

    private fun givenActivities(vararg infos: ResolveInfo) {
        every {
            packageManager.queryIntentActivities(any<Intent>(), any<PackageManager.ResolveInfoFlags>())
        } returns infos.toList()
    }

    @Test
    fun `ACTIVITY queries activities and returns their packages`() {
        givenActivities(activity(SubjectFixtures.BANK), activity(SubjectFixtures.SHOP))

        val packages = resolver.implicitPackages(IntentKind.ACTIVITY, Intent.ACTION_VIEW, "https://x", null)

        assertEquals(setOf(SubjectFixtures.BANK, SubjectFixtures.SHOP), packages)
        verify { packageManager.queryIntentActivities(any<Intent>(), any<PackageManager.ResolveInfoFlags>()) }
    }

    @Test
    fun `BROADCAST queries broadcast receivers`() {
        every {
            packageManager.queryBroadcastReceivers(any<Intent>(), any<PackageManager.ResolveInfoFlags>())
        } returns listOf(activity(SubjectFixtures.SHOP))

        val packages = resolver.implicitPackages(IntentKind.BROADCAST, "com.example.PING", null, null)

        assertEquals(setOf(SubjectFixtures.SHOP), packages)
    }

    @Test
    fun `SERVICE queries services and reads serviceInfo`() {
        every {
            packageManager.queryIntentServices(any<Intent>(), any<PackageManager.ResolveInfoFlags>())
        } returns listOf(service(SubjectFixtures.BANK))

        val packages = resolver.implicitPackages(IntentKind.SERVICE, "com.example.SYNC", null, null)

        assertEquals(setOf(SubjectFixtures.BANK), packages)
    }

    @Test
    fun `data and mime type are set together`() {
        givenActivities()

        resolver.implicitPackages(IntentKind.ACTIVITY, Intent.ACTION_VIEW, "https://x", "text/html")

        verify { anyConstructed<Intent>().setDataAndType(any(), "text/html") }
        verify(exactly = 0) { anyConstructed<Intent>().setData(any()) }
    }

    @Test
    fun `mime type alone sets the type`() {
        givenActivities()

        resolver.implicitPackages(IntentKind.ACTIVITY, Intent.ACTION_SEND, null, "text/plain")

        verify { anyConstructed<Intent>().setType("text/plain") }
        verify(exactly = 0) { anyConstructed<Intent>().setDataAndType(any(), any()) }
    }

    @Test
    fun `no match returns an empty set`() {
        givenActivities()

        assertTrue(resolver.implicitPackages(IntentKind.ACTIVITY, Intent.ACTION_VIEW, "https://x", null).isEmpty())
    }

    @Test
    fun `duplicate packages collapse`() {
        givenActivities(activity(SubjectFixtures.BANK), activity(SubjectFixtures.BANK))

        val packages = resolver.implicitPackages(IntentKind.ACTIVITY, Intent.ACTION_VIEW, "https://x", null)

        assertEquals(setOf(SubjectFixtures.BANK), packages)
    }
}
