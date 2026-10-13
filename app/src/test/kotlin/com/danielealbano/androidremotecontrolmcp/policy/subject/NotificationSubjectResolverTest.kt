package com.danielealbano.androidremotecontrolmcp.policy.subject

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicySubject
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.BANK
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.SHOP
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.request
import com.danielealbano.androidremotecontrolmcp.privacy.PlaceholderSubstitutor
import com.danielealbano.androidremotecontrolmcp.services.notifications.NotificationActionData
import com.danielealbano.androidremotecontrolmcp.services.notifications.NotificationData
import com.danielealbano.androidremotecontrolmcp.services.notifications.NotificationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NotificationSubjectResolverTest {
    private val provider = mockk<NotificationProvider>()
    private val substitutor =
        mockk<PlaceholderSubstitutor> {
            every { substitute(any()) } answers { firstArg<String>().replace("AMOUNT#1", "9000") }
        }
    private val resolver = NotificationSubjectResolver(provider, substitutor)

    init {
        every { provider.isReady() } returns true
        coEvery { provider.getNotifications(any(), any()) } returns
            listOf(
                notification("n1", BANK, listOf(NotificationActionData("a1", 0, "Responder", acceptsText = true))),
                notification("n2", SHOP, emptyList()),
            )
    }

    private fun notification(
        id: String,
        packageName: String,
        actions: List<NotificationActionData>,
    ): NotificationData =
        NotificationData(
            notificationId = id,
            packageName = packageName,
            appName = packageName,
            title = null,
            text = null,
            bigText = null,
            subText = null,
            timestamp = 0L,
            isOngoing = false,
            isClearable = true,
            category = null,
            groupKey = null,
            actions = actions,
        )

    private suspend fun resolve(
        policyClass: ToolPolicyClass,
        toolName: String,
        arguments: JsonObject,
        profiled: Set<String> = emptySet(),
    ): SubjectResolution = resolver.resolve(policyClass, request(toolName, arguments, profiled))

    private fun subjectOf(resolution: SubjectResolution): PolicySubject =
        (resolution as SubjectResolution.Resolved).subject

    private fun notificationId(id: String): JsonObject = buildJsonObject { put("notification_id", id) }

    @Test
    fun `listener not ready throws PermissionDenied`() =
        runTest {
            every { provider.isReady() } returns false

            val error =
                assertThrows<McpToolException.PermissionDenied> {
                    resolve(ToolPolicyClass.NOTIFICATION_LIST, "notification_list", buildJsonObject { })
                }

            assertEquals("Notification listener not enabled. Enable in Settings > Notification access.", error.message)
        }

    @Test
    fun `notification_list with package_name reads only that package`() =
        runTest {
            val arguments = buildJsonObject { put("package_name", SHOP) }

            val subject = subjectOf(resolve(ToolPolicyClass.NOTIFICATION_LIST, "notification_list", arguments))

            assertEquals(listOf(SHOP), subject.targets.map { it.packageName })
            coVerify(exactly = 0) { provider.getNotifications(any(), any()) }
        }

    @Test
    fun `notification_list without filter reads every notifying package`() =
        runTest {
            val subject =
                subjectOf(resolve(ToolPolicyClass.NOTIFICATION_LIST, "notification_list", buildJsonObject { }))

            assertEquals(PolicyActionType.READ, subject.actionType)
            assertEquals(listOf(BANK, SHOP), subject.targets.map { it.packageName })
        }

    @Test
    fun `notification_list with no notifications is NotAppScoped`() =
        runTest {
            coEvery { provider.getNotifications(any(), any()) } returns emptyList()

            assertEquals(
                SubjectResolution.NotAppScoped,
                resolve(ToolPolicyClass.NOTIFICATION_LIST, "notification_list", buildJsonObject { }),
            )
        }

    @Test
    fun `notification_open resolves NAVIGATE on the notification package`() =
        runTest {
            val subject =
                subjectOf(resolve(ToolPolicyClass.NOTIFICATION_OPEN, "notification_open", notificationId("n2")))

            assertEquals(PolicyActionType.NAVIGATE, subject.actionType)
            assertEquals(listOf(PolicyTargets.packageOnly(SHOP)), subject.targets)
        }

    @Test
    fun `notification_dismiss and notification_snooze resolve unclassified ACTION_BUTTON`() =
        runTest {
            listOf("notification_dismiss", "notification_snooze").forEach { tool ->
                val subject = subjectOf(resolve(ToolPolicyClass.NOTIFICATION_CONTROL, tool, notificationId("n1")))

                assertEquals(PolicyActionType.ACTION_BUTTON, subject.actionType)
                assertEquals(listOf(PolicyTargets.packageOnly(BANK)), subject.targets)
                assertFalse(subject.classifyTarget)
            }
        }

    @Test
    fun `notification_action resolves ACTION_BUTTON on the action's package`() =
        runTest {
            val arguments = buildJsonObject { put("action_id", "a1") }

            val subject = subjectOf(resolve(ToolPolicyClass.NOTIFICATION_ACTION, "notification_action", arguments))

            assertEquals(PolicyActionType.ACTION_BUTTON, subject.actionType)
            assertEquals(listOf(BANK), subject.targets.map { it.packageName })
        }

    @Test
    fun `notification_reply amountText is the substituted reply`() =
        runTest {
            val arguments =
                buildJsonObject {
                    put("action_id", "a1")
                    put("text", "AMOUNT#1")
                }

            val subject = subjectOf(resolve(ToolPolicyClass.NOTIFICATION_REPLY, "notification_reply", arguments))

            assertEquals("9000", subject.amountText)
        }

    @Test
    fun `unknown notification_id`() =
        runTest {
            assertEquals(
                SubjectResolution.Unattributed,
                resolve(ToolPolicyClass.NOTIFICATION_OPEN, "notification_open", notificationId("zz")),
            )
            assertEquals(
                SubjectResolution.Blocked(null, PolicyActionType.NAVIGATE, BlockCause.TARGET_UNRESOLVED),
                resolve(ToolPolicyClass.NOTIFICATION_OPEN, "notification_open", notificationId("zz"), setOf(BANK)),
            )
        }

    @Test
    fun `missing action_id throws InvalidParams`() =
        runTest {
            assertThrows<McpToolException.InvalidParams> {
                resolve(ToolPolicyClass.NOTIFICATION_ACTION, "notification_action", buildJsonObject { })
            }
        }
}
