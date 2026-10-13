package com.danielealbano.androidremotecontrolmcp.policy.subject

import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

class ToolSubjectResolverImpl
    @Inject
    constructor(
        windowResolver: WindowSubjectResolver,
        nodeResolver: NodeSubjectResolver,
        coordinateResolver: CoordinateSubjectResolver,
        textResolver: TextSubjectResolver,
        notificationResolver: NotificationSubjectResolver,
        launchResolver: LaunchSubjectResolver,
    ) : ToolSubjectResolver {
        private val resolvers: Map<ResolverKind, PolicySubjectResolver> =
            mapOf(
                ResolverKind.WINDOW to windowResolver,
                ResolverKind.NODE to nodeResolver,
                ResolverKind.COORDINATE to coordinateResolver,
                ResolverKind.TEXT to textResolver,
                ResolverKind.NOTIFICATION to notificationResolver,
                ResolverKind.LAUNCH to launchResolver,
            )

        override suspend fun resolve(
            toolName: String,
            arguments: JsonObject?,
            profiledPackages: Set<String>,
        ): SubjectResolution {
            val policyClass = ToolPolicyCatalog.requireClass(toolName)
            return when (policyClass) {
                ToolPolicyClass.NOT_APP_SCOPED -> {
                    SubjectResolution.NotAppScoped
                }

                ToolPolicyClass.EXEMPT_EXIT -> {
                    SubjectResolution.ExemptExit
                }

                else -> {
                    resolvers
                        .getValue(policyClass.kind)
                        .resolve(policyClass, SubjectRequest(toolName, arguments, profiledPackages))
                }
            }
        }
    }
