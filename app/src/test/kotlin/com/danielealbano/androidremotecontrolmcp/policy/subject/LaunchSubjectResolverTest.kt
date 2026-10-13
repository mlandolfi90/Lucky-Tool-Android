package com.danielealbano.androidremotecontrolmcp.policy.subject

import android.content.Intent
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.BANK
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.OWN_PACKAGE
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.SHOP
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.request
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LaunchSubjectResolverTest {
    private val intentTargets = mockk<IntentTargetResolver>()
    private val resolver = LaunchSubjectResolver(intentTargets)

    private suspend fun resolve(
        policyClass: ToolPolicyClass,
        toolName: String,
        arguments: JsonObject,
        profiled: Set<String> = emptySet(),
    ): SubjectResolution = resolver.resolve(policyClass, request(toolName, arguments, profiled))

    private suspend fun sendIntent(
        profiled: Set<String> = emptySet(),
        build: JsonObjectBuilder.() -> Unit,
    ): SubjectResolution = resolve(ToolPolicyClass.SEND_INTENT, "send_intent", buildJsonObject(build), profiled)

    private fun subjectOf(resolution: SubjectResolution) = (resolution as SubjectResolution.Resolved).subject

    @Test
    fun `open_app and close_app resolve NAVIGATE on package_id`() =
        runTest {
            listOf("open_app", "close_app").forEach { tool ->
                val subject =
                    subjectOf(resolve(ToolPolicyClass.APP_LAUNCH, tool, buildJsonObject { put("package_id", BANK) }))

                assertEquals(PolicyActionType.NAVIGATE, subject.actionType)
                assertEquals(listOf(PolicyTargets.packageOnly(BANK)), subject.targets)
            }
        }

    @Test
    fun `empty package_id throws InvalidParams`() =
        runTest {
            assertThrows<McpToolException.InvalidParams> {
                resolve(ToolPolicyClass.APP_LAUNCH, "open_app", buildJsonObject { put("package_id", "") })
            }
        }

    @Test
    fun `send_intent activity with component targets the component package`() =
        runTest {
            val subject =
                subjectOf(
                    sendIntent {
                        put("type", "activity")
                        put("component", "com.example.bank/.Pay")
                    },
                )

            assertEquals(listOf(BANK), subject.targets.map { it.packageName })
            verify(exactly = 0) { intentTargets.implicitPackages(any(), any(), any(), any()) }
        }

    @Test
    fun `send_intent activity amountText holds data and every extra value`() =
        runTest {
            val subject =
                subjectOf(
                    sendIntent {
                        put("type", "activity")
                        put("component", "com.example.bank/.Pay")
                        put("data", "upi://pay?am=1500")
                        putJsonObject("extras") {
                            put("amount", "2500")
                            putJsonArray("list") { add("10") }
                            put("flag", true)
                        }
                    },
                )

            val amountText = subject.amountText.orEmpty()
            listOf("upi://pay?am=1500", "2500", "10", "true").forEach { assertTrue(amountText.contains(it), it) }
            listOf("amount", "list", "flag").forEach { assertFalse(amountText.contains(it), it) }
        }

    @Test
    fun `send_intent implicit activity uses PackageManager resolution`() =
        runTest {
            every { intentTargets.implicitPackages(IntentKind.ACTIVITY, "com.example.PAY", "pay://x", null) } returns
                setOf(SHOP)

            val subject =
                subjectOf(
                    sendIntent {
                        put("type", "activity")
                        put("action", "com.example.PAY")
                        put("data", "pay://x")
                    },
                )

            assertEquals(listOf(SHOP), subject.targets.map { it.packageName })
            assertEquals(PolicyActionType.NAVIGATE, subject.actionType)
        }

    @Test
    fun `send_intent implicit activity without match`() =
        runTest {
            every { intentTargets.implicitPackages(any(), any(), any(), any()) } returns emptySet()
            val build: JsonObjectBuilder.() -> Unit = {
                put("type", "activity")
                put("action", "com.example.NONE")
            }

            assertEquals(
                SubjectResolution.Blocked(null, PolicyActionType.NAVIGATE, BlockCause.UNRESOLVED_IMPLICIT_INTENT),
                sendIntent(setOf(BANK), build),
            )
            assertEquals(SubjectResolution.Unattributed, sendIntent(build = build))
        }

    @Test
    fun `send_intent broadcast to a profiled package is Blocked`() =
        runTest {
            val resolution =
                sendIntent(setOf(BANK)) {
                    put("type", "broadcast")
                    put("package", BANK)
                }

            assertEquals(
                SubjectResolution.Blocked(
                    BANK,
                    PolicyActionType.ACTION_BUTTON,
                    BlockCause.BACKGROUND_COMPONENT_IN_PROFILED_APP,
                ),
                resolution,
            )
        }

    @Test
    fun `send_intent service to an unprofiled package resolves package-only ACTION_BUTTON`() =
        runTest {
            val subject =
                subjectOf(
                    sendIntent(setOf(BANK)) {
                        put("type", "service")
                        put("component", "com.example.shop/.Sync")
                    },
                )

            assertEquals(PolicyActionType.ACTION_BUTTON, subject.actionType)
            assertEquals(listOf(PolicyTargets.packageOnly(SHOP)), subject.targets)
            assertFalse(subject.classifyTarget)
        }

    @Test
    fun `send_intent implicit broadcast with profiles is Blocked without querying`() =
        runTest {
            val resolution =
                sendIntent(setOf(BANK)) {
                    put("type", "broadcast")
                    put("action", "com.example.PING")
                }

            assertEquals(
                SubjectResolution.Blocked(null, PolicyActionType.ACTION_BUTTON, BlockCause.UNRESOLVED_IMPLICIT_INTENT),
                resolution,
            )
            verify(exactly = 0) { intentTargets.implicitPackages(any(), any(), any(), any()) }
        }

    @Test
    fun `send_intent implicit broadcast without profiles resolves the queried receivers`() =
        runTest {
            every { intentTargets.implicitPackages(IntentKind.BROADCAST, "com.example.PING", null, null) } returns
                setOf(SHOP, OWN_PACKAGE)

            val subject =
                subjectOf(
                    sendIntent {
                        put("type", "broadcast")
                        put("action", "com.example.PING")
                    },
                )

            assertEquals(setOf(SHOP, OWN_PACKAGE), subject.targets.map { it.packageName }.toSet())
        }

    @Test
    fun `send_intent invalid type throws InvalidParams`() =
        runTest {
            val error = assertThrows<McpToolException.InvalidParams> { sendIntent { put("type", "job") } }

            assertEquals("type must be 'activity', 'broadcast', or 'service'", error.message)
        }

    @Test
    fun `open_uri with package_name targets it and amountText is the uri`() =
        runTest {
            val arguments =
                buildJsonObject {
                    put("uri", "https://bank.example/pay?amount=1500")
                    put("package_name", BANK)
                }

            val subject = subjectOf(resolve(ToolPolicyClass.OPEN_URI, "open_uri", arguments))

            assertEquals(listOf(BANK), subject.targets.map { it.packageName })
            assertEquals("https://bank.example/pay?amount=1500", subject.amountText)
        }

    @Test
    fun `open_uri without package_name targets every ACTION_VIEW candidate`() =
        runTest {
            val uri = "https://bank.example/pay"
            every { intentTargets.implicitPackages(IntentKind.ACTIVITY, Intent.ACTION_VIEW, uri, "text/html") } returns
                setOf(BANK, SHOP)
            val arguments =
                buildJsonObject {
                    put("uri", uri)
                    put("mime_type", "text/html")
                }

            val subject = subjectOf(resolve(ToolPolicyClass.OPEN_URI, "open_uri", arguments))

            assertEquals(setOf(BANK, SHOP), subject.targets.map { it.packageName }.toSet())
        }
}
