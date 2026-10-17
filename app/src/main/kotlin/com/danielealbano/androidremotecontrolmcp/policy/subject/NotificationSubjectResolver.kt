package com.danielealbano.androidremotecontrolmcp.policy.subject

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.mcp.tools.McpToolUtils
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.privacy.PlaceholderSubstitutor
import com.danielealbano.androidremotecontrolmcp.services.notifications.NotificationProvider
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

/**
 * Notification tools act on the posting app (`sbn.packageName`): list → READ (one target per notifying app when
 * unfiltered), open → NAVIGATE, dismiss/snooze/action → ACTION_BUTTON, reply → ACTION_BUTTON with the substituted
 * reply as amountText. None is classified by label.
 */
class NotificationSubjectResolver
    @Inject
    constructor(
        private val notificationProvider: NotificationProvider,
        private val substitutor: PlaceholderSubstitutor,
    ) : PolicySubjectResolver {
        override suspend fun resolve(
            policyClass: ToolPolicyClass,
            request: SubjectRequest,
        ): SubjectResolution {
            if (!notificationProvider.isReady()) throw McpToolException.PermissionDenied(NOT_READY_MESSAGE)
            val arguments = request.arguments
            val profiled = request.profiledPackages
            return when (policyClass) {
                ToolPolicyClass.NOTIFICATION_LIST -> {
                    listSubject(arguments, profiled)
                }

                ToolPolicyClass.NOTIFICATION_OPEN -> {
                    notificationSubject(notificationId(arguments), PolicyActionType.NAVIGATE, profiled)
                }

                ToolPolicyClass.NOTIFICATION_CONTROL -> {
                    notificationSubject(notificationId(arguments), PolicyActionType.ACTION_BUTTON, profiled)
                }

                ToolPolicyClass.NOTIFICATION_ACTION -> {
                    actionSubject(McpToolUtils.requireString(arguments, PARAM_ACTION_ID), null, profiled)
                }

                ToolPolicyClass.NOTIFICATION_REPLY -> {
                    actionSubject(
                        McpToolUtils.requireString(arguments, PARAM_ACTION_ID),
                        substitutor.substitute(McpToolUtils.requireString(arguments, PARAM_TEXT)),
                        profiled,
                    )
                }

                else -> {
                    error("Unsupported policy class for NotificationSubjectResolver: $policyClass")
                }
            }
        }

        private fun notificationId(arguments: JsonObject?): String =
            McpToolUtils.requireString(
                arguments,
                PARAM_NOTIFICATION_ID,
            )

        private suspend fun listSubject(
            arguments: JsonObject?,
            profiledPackages: Set<String>,
        ): SubjectResolution {
            val filter = McpToolUtils.optionalString(arguments, PARAM_PACKAGE_NAME, "").ifEmpty { null }
            val packages =
                if (filter != null) {
                    listOf(filter)
                } else {
                    notificationProvider.getNotifications().map { it.packageName }.distinct()
                }
            return if (packages.isEmpty()) {
                SubjectResolution.NotAppScoped
            } else {
                SubjectSupport.resolved(
                    PolicyActionType.READ,
                    packages.map(PolicyTargets::packageOnly),
                    profiledPackages,
                )
            }
        }

        private suspend fun notificationSubject(
            notificationId: String,
            actionType: PolicyActionType,
            profiledPackages: Set<String>,
        ): SubjectResolution {
            val packageName =
                notificationProvider
                    .getNotifications()
                    .firstOrNull { it.notificationId == notificationId }
                    ?.packageName
            return packageSubject(packageName, actionType, profiledPackages, null)
        }

        private suspend fun actionSubject(
            actionId: String,
            replyText: String?,
            profiledPackages: Set<String>,
        ): SubjectResolution {
            val packageName =
                notificationProvider
                    .getNotifications()
                    .firstOrNull { notification -> notification.actions.any { it.actionId == actionId } }
                    ?.packageName
            return packageSubject(packageName, PolicyActionType.ACTION_BUTTON, profiledPackages, replyText)
        }

        private fun packageSubject(
            packageName: String?,
            actionType: PolicyActionType,
            profiledPackages: Set<String>,
            amountText: String?,
        ): SubjectResolution =
            if (packageName.isNullOrBlank()) {
                SubjectSupport.unattributed(actionType, profiledPackages)
            } else {
                SubjectSupport.resolved(
                    actionType,
                    listOf(PolicyTargets.packageOnly(packageName)),
                    profiledPackages,
                    amountText,
                )
            }

        companion object {
            private const val NOT_READY_MESSAGE =
                "Notification listener not enabled. Enable in Settings > Notification access."
            private const val PARAM_PACKAGE_NAME = "package_name"
            private const val PARAM_NOTIFICATION_ID = "notification_id"
            private const val PARAM_ACTION_ID = "action_id"
            private const val PARAM_TEXT = "text"
        }
    }
