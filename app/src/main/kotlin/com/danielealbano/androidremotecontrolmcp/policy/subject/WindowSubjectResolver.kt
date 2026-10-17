package com.danielealbano.androidremotecontrolmcp.policy.subject

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.privacy.PlaceholderSubstitutor
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/** Screen reads (every window), press_back (focused app window), set_clipboard (focused app window if profiled). */
class WindowSubjectResolver
    @Inject
    constructor(
        private val windowSource: PolicyWindowSource,
        private val substitutor: PlaceholderSubstitutor,
    ) : PolicySubjectResolver {
        override suspend fun resolve(
            policyClass: ToolPolicyClass,
            request: SubjectRequest,
        ): SubjectResolution =
            when (policyClass) {
                ToolPolicyClass.SCREEN_READ -> {
                    SubjectSupport.allWindowsRead(windowSource.readWindows(), request.profiledPackages)
                }

                ToolPolicyClass.PRESS_BACK -> {
                    SubjectSupport.focusedAppSubject(
                        windowSource.readWindows(),
                        PolicyActionType.NAVIGATE,
                        request.profiledPackages,
                    )
                }

                ToolPolicyClass.CLIPBOARD_WRITE -> {
                    clipboardSubject(request)
                }

                else -> {
                    error("Unsupported policy class for WindowSubjectResolver: $policyClass")
                }
            }

        private fun clipboardSubject(request: SubjectRequest): SubjectResolution {
            val text =
                request.arguments
                    ?.get(PARAM_TEXT)
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.let { substitutor.substitute(it) }
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'text'")
            val result = windowSource.readWindows()
            val focused = if (result.degraded) null else SubjectSupport.focusedAppPackage(result.windows)
            return when {
                focused == null -> {
                    SubjectSupport.unattributed(PolicyActionType.TYPE, request.profiledPackages)
                }

                focused in request.profiledPackages -> {
                    SubjectSupport.resolved(
                        PolicyActionType.TYPE,
                        listOf(PolicyTargets.packageOnly(focused)),
                        request.profiledPackages,
                        text,
                    )
                }

                else -> {
                    SubjectResolution.NotAppScoped
                }
            }
        }

        companion object {
            private const val PARAM_TEXT = "text"
        }
    }
