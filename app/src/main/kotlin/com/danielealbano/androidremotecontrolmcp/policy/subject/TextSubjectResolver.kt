package com.danielealbano.androidremotecontrolmcp.policy.subject

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.mcp.tools.McpToolUtils
import com.danielealbano.androidremotecontrolmcp.mcp.tools.TypeAppendTextTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.TypeClearTextTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.TypeInsertTextTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.TypeReplaceTextTool
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.privacy.PlaceholderSubstitutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/** type_* tools (TYPE on the node_id field, amountText = final value) and press_key (per key). */
class TextSubjectResolver
    @Inject
    constructor(
        private val windowSource: PolicyWindowSource,
        private val elementFinder: ElementFinder,
        private val substitutor: PlaceholderSubstitutor,
    ) : PolicySubjectResolver {
        /** Edit a type tool applies to the field value; holds real (substituted) text, never logged. */
        private sealed interface FieldEdit {
            fun applyTo(current: String): String

            class Append(
                private val text: String,
            ) : FieldEdit {
                override fun applyTo(current: String): String = current + text
            }

            class Insert(
                private val text: String,
                private val offset: Int,
            ) : FieldEdit {
                override fun applyTo(current: String): String {
                    val at = offset.coerceIn(0, current.length)
                    return current.substring(0, at) + text + current.substring(at)
                }
            }

            /** Search not found in the tree value → both values are evaluated (largest number wins). */
            class Replace(
                private val search: String,
                private val newText: String,
            ) : FieldEdit {
                override fun applyTo(current: String): String {
                    val index = current.indexOf(search)
                    return if (index < 0) {
                        current + UNMATCHED_SEPARATOR + newText
                    } else {
                        current.replaceRange(index, index + search.length, newText)
                    }
                }
            }

            data object Clear : FieldEdit {
                override fun applyTo(current: String): String = ""
            }
        }

        override suspend fun resolve(
            policyClass: ToolPolicyClass,
            request: SubjectRequest,
        ): SubjectResolution =
            when (policyClass) {
                ToolPolicyClass.TEXT_EDIT -> textEdit(request)
                ToolPolicyClass.PRESS_KEY -> pressKey(request)
                else -> error("Unsupported policy class for TextSubjectResolver: $policyClass")
            }

        private fun textEdit(request: SubjectRequest): SubjectResolution {
            val nodeId = McpToolUtils.requireString(request.arguments, PARAM_NODE_ID)
            if (nodeId.isEmpty()) throw McpToolException.InvalidParams("Parameter 'node_id' must be non-empty")
            val edit = fieldEditOf(request.toolName, request.arguments)
            val result = windowSource.readWindows()
            if (result.degraded) return SubjectSupport.unattributed(PolicyActionType.TYPE, request.profiledPackages)
            val location =
                SubjectSupport.locate(elementFinder, result.windows, nodeId)
                    ?: throw McpToolException.NodeNotFound("Node '$nodeId' not found in accessibility tree")
            return fieldSubject(location, edit.applyTo(currentValue(location.node)), request.profiledPackages)
        }

        private fun fieldSubject(
            location: NodeLocation,
            finalValue: String,
            profiledPackages: Set<String>,
        ): SubjectResolution {
            val packageName = SubjectSupport.packageOf(location.window)
            return if (packageName == null) {
                SubjectSupport.unattributed(PolicyActionType.TYPE, profiledPackages)
            } else {
                SubjectSupport.resolved(
                    PolicyActionType.TYPE,
                    listOf(PolicyTargets.field(packageName, location.node)),
                    profiledPackages,
                    finalValue,
                )
            }
        }

        private fun fieldEditOf(
            toolName: String,
            arguments: JsonObject?,
        ): FieldEdit =
            when (toolName) {
                TypeAppendTextTool.TOOL_NAME -> {
                    FieldEdit.Append(substituted(arguments, PARAM_TEXT))
                }

                TypeInsertTextTool.TOOL_NAME -> {
                    FieldEdit.Insert(
                        text = substituted(arguments, PARAM_TEXT),
                        offset = McpToolUtils.requireInt(arguments, PARAM_OFFSET),
                    )
                }

                TypeReplaceTextTool.TOOL_NAME -> {
                    FieldEdit.Replace(substituted(arguments, PARAM_SEARCH), substituted(arguments, PARAM_NEW_TEXT))
                }

                TypeClearTextTool.TOOL_NAME -> {
                    FieldEdit.Clear
                }

                else -> {
                    error("Unsupported text tool: $toolName")
                }
            }

        private fun substituted(
            arguments: JsonObject?,
            name: String,
        ): String = substitutor.substitute(McpToolUtils.requireString(arguments, name))

        private fun currentValue(node: AccessibilityNodeData): String =
            node.text
                ?.takeUnless { it == node.hintText }
                .orEmpty()

        private fun pressKey(request: SubjectRequest): SubjectResolution {
            val key =
                request.arguments
                    ?.get(PARAM_KEY)
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'key'")
            val profiled = request.profiledPackages
            return when (key.uppercase()) {
                KEY_HOME -> {
                    SubjectResolution.ExemptExit
                }

                KEY_BACK -> {
                    SubjectSupport.focusedAppSubject(windowSource.readWindows(), PolicyActionType.NAVIGATE, profiled)
                }

                KEY_ENTER -> {
                    focusedFieldSubject(profiled, ENTER_NOT_FOCUSED, null)
                }

                KEY_DEL -> {
                    focusedFieldSubject(profiled, DEL_NOT_FOCUSED) { it.dropLast(1) }
                }

                KEY_TAB -> {
                    focusedFieldSubject(profiled, KEY_NOT_FOCUSED) { it + TAB_CHAR }
                }

                KEY_SPACE -> {
                    focusedFieldSubject(profiled, KEY_NOT_FOCUSED) { it + SPACE_CHAR }
                }

                else -> {
                    throw McpToolException.InvalidParams("Invalid key: '$key'. Allowed values: $ALLOWED_KEYS")
                }
            }
        }

        /** [edit] null = ENTER (package-only, unclassified ACTION_BUTTON); otherwise TYPE with the edited value. */
        private fun focusedFieldSubject(
            profiledPackages: Set<String>,
            missingMessage: String,
            edit: ((String) -> String)?,
        ): SubjectResolution {
            val field = windowSource.focusedEditableField() ?: throw McpToolException.NodeNotFound(missingMessage)
            val packageName = field.packageName?.takeIf { it.isNotBlank() }
            val actionType = if (edit == null) PolicyActionType.ACTION_BUTTON else PolicyActionType.TYPE
            return when {
                packageName == null -> {
                    SubjectSupport.unattributed(actionType, profiledPackages)
                }

                edit == null -> {
                    SubjectSupport.resolved(
                        actionType,
                        listOf(PolicyTargets.packageOnly(packageName)),
                        profiledPackages,
                    )
                }

                else -> {
                    SubjectSupport.resolved(
                        actionType,
                        listOf(PolicyTargets.field(packageName, field.node)),
                        profiledPackages,
                        edit(field.node.text.orEmpty()),
                    )
                }
            }
        }

        companion object {
            private const val UNMATCHED_SEPARATOR = "\n"
            private const val PARAM_NODE_ID = "node_id"
            private const val PARAM_TEXT = "text"
            private const val PARAM_OFFSET = "offset"
            private const val PARAM_SEARCH = "search"
            private const val PARAM_NEW_TEXT = "new_text"
            private const val PARAM_KEY = "key"
            private const val KEY_ENTER = "ENTER"
            private const val KEY_BACK = "BACK"
            private const val KEY_DEL = "DEL"
            private const val KEY_HOME = "HOME"
            private const val KEY_TAB = "TAB"
            private const val KEY_SPACE = "SPACE"
            private const val ALLOWED_KEYS = "ENTER, BACK, DEL, HOME, TAB, SPACE"
            private const val TAB_CHAR = '\t'
            private const val SPACE_CHAR = ' '
            private const val ENTER_NOT_FOCUSED = "No focused node found for ENTER key"
            private const val DEL_NOT_FOCUSED = "No focused editable node found for DEL key"
            private const val KEY_NOT_FOCUSED = "No focused editable node found for key input"
        }
    }
