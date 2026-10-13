package com.danielealbano.androidremotecontrolmcp.policy.subject

import android.content.Intent
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.mcp.tools.McpToolUtils
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject

/**
 * open_app/close_app → NAVIGATE on package_id. send_intent/open_uri → targets from the explicit component/package or
 * PackageManager resolution: activity = NAVIGATE (amountText = data + every extra value / the URI); broadcast/service
 * into a profiled package → FORBIDDEN; an implicit broadcast/service while ≥1 profile exists → FORBIDDEN without a
 * query (runtime receivers are invisible to PackageManager, R11); unresolvable implicit activity while ≥1 profile
 * exists → FORBIDDEN.
 */
class LaunchSubjectResolver
    @Inject
    constructor(
        private val intentTargetResolver: IntentTargetResolver,
    ) : PolicySubjectResolver {
        override suspend fun resolve(
            policyClass: ToolPolicyClass,
            request: SubjectRequest,
        ): SubjectResolution =
            when (policyClass) {
                ToolPolicyClass.APP_LAUNCH -> appLaunch(request)
                ToolPolicyClass.SEND_INTENT -> sendIntent(request)
                ToolPolicyClass.OPEN_URI -> openUri(request)
                else -> error("Unsupported policy class for LaunchSubjectResolver: $policyClass")
            }

        private fun appLaunch(request: SubjectRequest): SubjectResolution {
            val packageId = McpToolUtils.requireString(request.arguments, PARAM_PACKAGE_ID)
            if (packageId.isEmpty()) throw McpToolException.InvalidParams("Parameter 'package_id' must not be empty")
            return SubjectSupport.resolved(
                PolicyActionType.NAVIGATE,
                listOf(PolicyTargets.packageOnly(packageId)),
                request.profiledPackages,
            )
        }

        private fun sendIntent(request: SubjectRequest): SubjectResolution {
            val arguments = request.arguments
            val type = McpToolUtils.requireString(arguments, PARAM_TYPE)
            val kind = IntentKind.fromWireName(type) ?: throw McpToolException.InvalidParams(INVALID_TYPE_MESSAGE)
            val action = optionalNonBlank(arguments, PARAM_ACTION)
            val data = optionalNonBlank(arguments, PARAM_DATA)
            val component = optionalNonBlank(arguments, PARAM_COMPONENT)
            val explicit = explicitPackages(component, optionalNonBlank(arguments, PARAM_PACKAGE))
            return when (kind) {
                IntentKind.ACTIVITY -> {
                    val packages = explicit.ifEmpty { intentTargetResolver.implicitPackages(kind, action, data, null) }
                    activitySubject(packages, intentAmountText(data, arguments), request.profiledPackages)
                }

                IntentKind.BROADCAST, IntentKind.SERVICE -> {
                    backgroundSubject(explicit, ImplicitQuery(kind, action, data), request.profiledPackages)
                }
            }
        }

        private fun openUri(request: SubjectRequest): SubjectResolution {
            val arguments = request.arguments
            val uri = McpToolUtils.requireString(arguments, PARAM_URI)
            val packageName = McpToolUtils.optionalString(arguments, PARAM_PACKAGE_NAME, "").ifEmpty { null }
            val mimeType = McpToolUtils.optionalString(arguments, PARAM_MIME_TYPE, "").ifEmpty { null }
            val packages =
                if (packageName != null) {
                    setOf(packageName)
                } else {
                    intentTargetResolver.implicitPackages(IntentKind.ACTIVITY, Intent.ACTION_VIEW, uri, mimeType)
                }
            return activitySubject(packages, uri, request.profiledPackages)
        }

        private fun activitySubject(
            packages: Set<String>,
            amountText: String,
            profiledPackages: Set<String>,
        ): SubjectResolution =
            if (packages.isEmpty()) {
                SubjectSupport.unattributed(
                    PolicyActionType.NAVIGATE,
                    profiledPackages,
                    BlockCause.UNRESOLVED_IMPLICIT_INTENT,
                )
            } else {
                SubjectSupport.resolved(
                    PolicyActionType.NAVIGATE,
                    packages.map(PolicyTargets::packageOnly),
                    profiledPackages,
                    amountText,
                )
            }

        private fun backgroundSubject(
            explicit: Set<String>,
            query: ImplicitQuery,
            profiledPackages: Set<String>,
        ): SubjectResolution {
            val profiledTarget = explicit.firstOrNull { it in profiledPackages }
            val packages =
                if (explicit.isEmpty() && profiledPackages.isEmpty()) {
                    intentTargetResolver.implicitPackages(query.kind, query.action, query.data, null)
                } else {
                    explicit
                }
            return when {
                profiledTarget != null -> {
                    SubjectResolution.Blocked(
                        profiledTarget,
                        PolicyActionType.ACTION_BUTTON,
                        BlockCause.BACKGROUND_COMPONENT_IN_PROFILED_APP,
                    )
                }

                packages.isEmpty() -> {
                    SubjectSupport.unattributed(
                        PolicyActionType.ACTION_BUTTON,
                        profiledPackages,
                        BlockCause.UNRESOLVED_IMPLICIT_INTENT,
                    )
                }

                else -> {
                    SubjectSupport.resolved(
                        PolicyActionType.ACTION_BUTTON,
                        packages.map(PolicyTargets::packageOnly),
                        profiledPackages,
                    )
                }
            }
        }

        private fun explicitPackages(
            component: String?,
            packageName: String?,
        ): Set<String> =
            setOfNotNull(
                component?.substringBefore(COMPONENT_SEPARATOR, "")?.takeIf { it.isNotEmpty() },
                packageName,
            )

        /** Data URI plus every extra VALUE (keys excluded), one per line; the engine takes the largest number. */
        private fun intentAmountText(
            data: String?,
            arguments: JsonObject?,
        ): String {
            val extras = arguments?.get(PARAM_EXTRAS) as? JsonObject
            return (listOfNotNull(data) + extras?.values?.flatMap(::valueTexts).orEmpty())
                .joinToString(AMOUNT_TEXT_SEPARATOR)
        }

        private fun valueTexts(element: JsonElement): List<String> =
            when (element) {
                is JsonPrimitive -> listOf(element.content)
                is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.content }
                is JsonObject -> emptyList()
            }

        private fun optionalNonBlank(
            arguments: JsonObject?,
            name: String,
        ): String? = McpToolUtils.optionalString(arguments, name, "").ifBlank { null }

        /** Not a data class: [data] may carry an amount and must never reach a generated toString. */
        private class ImplicitQuery(
            val kind: IntentKind,
            val action: String?,
            val data: String?,
        )

        companion object {
            private const val PARAM_PACKAGE_ID = "package_id"
            private const val PARAM_TYPE = "type"
            private const val PARAM_ACTION = "action"
            private const val PARAM_DATA = "data"
            private const val PARAM_COMPONENT = "component"
            private const val PARAM_PACKAGE = "package"
            private const val PARAM_EXTRAS = "extras"
            private const val PARAM_URI = "uri"
            private const val PARAM_PACKAGE_NAME = "package_name"
            private const val PARAM_MIME_TYPE = "mime_type"
            private const val COMPONENT_SEPARATOR = '/'
            private const val AMOUNT_TEXT_SEPARATOR = "\n"
            private const val INVALID_TYPE_MESSAGE = "type must be 'activity', 'broadcast', or 'service'"
        }
    }
