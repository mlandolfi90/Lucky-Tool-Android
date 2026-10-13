package com.danielealbano.androidremotecontrolmcp.services.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MockKExtension::class)
@DisplayName("AppIconCache")
class AppIconCacheTest {
    @MockK
    private lateinit var mockContext: Context

    @MockK
    private lateinit var mockPackageManager: PackageManager

    private lateinit var appIconCache: AppIconCache

    @BeforeEach
    fun setUp() {
        every { mockContext.packageManager } returns mockPackageManager
        appIconCache = AppIconCache(mockContext)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    /**
     * Creates a [ResolveInfo] whose [ResolveInfo.activityInfo] carries [packageName]
     * and whose [ResolveInfo.loadLabel] returns [label].
     */
    private fun createResolveInfo(
        packageName: String,
        label: CharSequence,
    ): ResolveInfo {
        val activityInfo = ActivityInfo()
        activityInfo.packageName = packageName
        val resolveInfo = mockk<ResolveInfo>()
        resolveInfo.activityInfo = activityInfo
        every { resolveInfo.loadLabel(mockPackageManager) } returns label
        return resolveInfo
    }

    private fun givenLaunchableActivities(vararg resolveInfos: ResolveInfo) {
        every {
            mockPackageManager.queryIntentActivities(any<Intent>(), any<PackageManager.ResolveInfoFlags>())
        } returns resolveInfos.toList()
    }

    @Nested
    @DisplayName("getLaunchableApps")
    inner class GetLaunchableApps {
        @Test
        fun `getLaunchableApps returns loadLabel text as app name`() {
            // Arrange
            givenLaunchableActivities(createResolveInfo("com.example.alpha", StringBuilder("Alpha")))

            // Act
            val apps = appIconCache.getLaunchableApps()

            // Assert
            assertEquals(listOf("com.example.alpha" to "Alpha"), apps)
        }

        @Test
        fun `getLaunchableApps skips blank labels and labels equal to package name`() {
            // Arrange
            givenLaunchableActivities(
                createResolveInfo("com.example.blank", "   "),
                createResolveInfo("com.example.same", "com.example.same"),
                createResolveInfo("com.example.valid", "Valid"),
            )

            // Act
            val apps = appIconCache.getLaunchableApps()

            // Assert
            assertEquals(listOf("com.example.valid" to "Valid"), apps)
        }

        @Test
        fun `getLaunchableApps skips entries without activityInfo`() {
            // Arrange
            val withoutActivityInfo = mockk<ResolveInfo>()
            withoutActivityInfo.activityInfo = null
            givenLaunchableActivities(
                withoutActivityInfo,
                createResolveInfo("com.example.valid", "Valid"),
            )

            // Act
            val apps = appIconCache.getLaunchableApps()

            // Assert
            assertEquals(listOf("com.example.valid" to "Valid"), apps)
        }

        @Test
        fun `getLaunchableApps dedupes by package and sorts case-insensitively`() {
            // Arrange
            givenLaunchableActivities(
                createResolveInfo("com.example.charlie", "Charlie"),
                createResolveInfo("com.example.beta", "beta"),
                createResolveInfo("com.example.alpha", "Alpha"),
                createResolveInfo("com.example.beta", "Beta Duplicate"),
            )

            // Act
            val apps = appIconCache.getLaunchableApps()

            // Assert
            assertEquals(
                listOf(
                    "com.example.alpha" to "Alpha",
                    "com.example.beta" to "beta",
                    "com.example.charlie" to "Charlie",
                ),
                apps,
            )
        }
    }
}
