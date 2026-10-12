package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.privacy.PlaceholderSubstitutor
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyToolGate
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.FindBy
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

/**
 * Selector form of a node target. [value] is the agent-supplied value with pseudonym placeholders reversed; it is
 * used ONLY for matching and MUST NEVER be echoed or logged (nor is the agent-supplied form).
 */
data class NodeSelector(
    val findBy: FindBy,
    val by: String,
    val value: String,
    val exactMatch: Boolean,
) {
    override fun toString(): String = "NodeSelector(by=$by, exactMatch=$exactMatch)"
}

/** Target of a node action: an explicit node ID or a selector resolved against the current tree. */
sealed interface NodeTarget {
    data class ById(
        val nodeId: String,
    ) : NodeTarget

    data class BySelector(
        val selector: NodeSelector,
    ) : NodeTarget
}

/** Outcome of resolving a [NodeSelector] (zero on-screen matches throws instead). */
sealed interface SelectorResolution {
    data class Resolved(
        val element: ElementInfo,
    ) : SelectorResolution

    /** [matches] is the full match list in tree order (off-screen included); [onScreenCount] is > 1. */
    data class Ambiguous(
        val matches: List<ElementInfo>,
        val onScreenCount: Int,
    ) : SelectorResolution
}

/**
 * Parses and resolves the target of click_node / tap_node: `node_id` OR selector (`by` + `value` [+ `exact_match`]).
 * A selector resolves among on-screen nodes only ([ElementInfo.visible]) across all windows, against the windows
 * of the caller's single tree read. More than one on-screen match is never guessed.
 */
class NodeSelectorResolver
    @Inject
    constructor(
        private val elementFinder: ElementFinder,
        private val privacyToolGate: PrivacyToolGate,
        private val substitutor: PlaceholderSubstitutor,
    ) {
        /**
         * Validates the target arguments; performs no tree read. Throws [McpToolException.InvalidParams] for missing,
         * empty or conflicting targets; non-primitive values fail like the other node tools.
         */
        fun parseTarget(arguments: JsonObject?): NodeTarget {
            val nodeId = arguments?.get("node_id")?.jsonPrimitive?.contentOrNull
            val byStr = arguments?.get("by")?.jsonPrimitive?.contentOrNull
            val rawValue = arguments?.get("value")?.jsonPrimitive?.contentOrNull
            val exactMatchArg = arguments?.get("exact_match")?.takeUnless { it is JsonNull }
            val hasSelector = byStr != null || rawValue != null
            if (nodeId == null) {
                if (!hasSelector) invalid("Missing required parameter 'node_id' (or a selector: 'by' + 'value')")
                return NodeTarget.BySelector(parseSelector(byStr, rawValue, exactMatchArg))
            }
            if (hasSelector) invalid("Provide either 'node_id' or a selector ('by' + 'value'), not both")
            if (exactMatchArg != null) {
                invalid("Parameter 'exact_match' is only valid with a selector ('by' + 'value')")
            }
            if (nodeId.isEmpty()) invalid("Parameter 'node_id' must be non-empty")
            return NodeTarget.ById(nodeId)
        }

        /**
         * Resolves [selector] among the on-screen nodes of [windows].
         *
         * @throws McpToolException.NodeNotFound when no on-screen node matches.
         */
        fun resolve(
            windows: List<WindowData>,
            selector: NodeSelector,
        ): SelectorResolution {
            val matches = elementFinder.findElements(windows, selector.findBy, selector.value, selector.exactMatch)
            val onScreen = matches.filter { it.visible }
            Log.d(TAG, "resolve: by=${selector.by}, matches=${matches.size}, onScreen=${onScreen.size}")
            return when {
                onScreen.size == 1 -> SelectorResolution.Resolved(onScreen.single())
                onScreen.size > 1 -> SelectorResolution.Ambiguous(matches, onScreen.size)
                else -> throw McpToolException.NodeNotFound(notFoundMessage(selector, matches))
            }
        }

        /**
         * Builds the ambiguous-selector error: untrusted warning first, candidates redacted by Privacy Mode. The
         * redaction input is the full match list, i.e. exactly what find_nodes redacts for the same selector.
         */
        suspend fun ambiguityResult(ambiguous: SelectorResolution.Ambiguous): CallToolResult {
            val matches = ambiguous.matches
            val redactedFields =
                privacyToolGate.texts(
                    matches.flatMap { listOf(it.text to "node text", it.contentDescription to "node description") },
                )
            val candidates =
                matches
                    .mapIndexed { index, element ->
                        element.copy(
                            text = redactedFields[index * 2],
                            contentDescription = redactedFields[index * 2 + 1],
                        )
                    }.filter { it.visible }
                    .take(MAX_AMBIGUOUS_CANDIDATES)
            val resultJson =
                buildJsonObject {
                    put("error", "ambiguous_selector")
                    put("match_count", ambiguous.onScreenCount)
                    put("candidates", buildJsonArray { candidates.forEach { add(McpToolUtils.buildNodeJson(it)) } })
                    put("hint", AMBIGUITY_HINT)
                }
            return McpToolUtils.untrustedErrorResult(Json.encodeToString(resultJson))
        }

        private fun parseSelector(
            byStr: String?,
            rawValue: String?,
            exactMatchArg: JsonElement?,
        ): NodeSelector {
            if (byStr == null) invalid("Missing required parameter 'by' $SELECTOR_PAIR_NOTE")
            if (rawValue == null) invalid("Missing required parameter 'value' $SELECTOR_PAIR_NOTE")
            if (rawValue.isEmpty()) invalid("Parameter 'value' must be non-empty")
            val findBy =
                mapFindBy(byStr)
                    ?: invalid(
                        "Invalid 'by' value: '$byStr'. Must be one of: text, content_desc, resource_id, class_name",
                    )
            return NodeSelector(
                findBy = findBy,
                by = byStr,
                value = substitutor.substitute(rawValue),
                exactMatch = exactMatchArg?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }

        private fun notFoundMessage(
            selector: NodeSelector,
            offScreenMatches: List<ElementInfo>,
        ): String {
            // No selector value here: the SDK logs thrown tool errors (message included) to logcat.
            val base = "No on-screen node matches selector (by=${selector.by}, exact_match=${selector.exactMatch})"
            if (offScreenMatches.isEmpty()) return base
            val ids = offScreenMatches.take(MAX_OFFSCREEN_IDS).joinToString(", ") { it.id }
            return "$base; ${offScreenMatches.size} matching node(s) are off-screen: [$ids]. " +
                "Call scroll_to_node with one of these node_ids first"
        }

        private fun invalid(message: String): Nothing = throw McpToolException.InvalidParams(message)

        companion object {
            private const val TAG = "MCP:NodeSelectorResolver"
            const val MAX_AMBIGUOUS_CANDIDATES = 5
            const val MAX_OFFSCREEN_IDS = 5
            private const val SELECTOR_PAIR_NOTE = "(a selector requires both 'by' and 'value')"
            private const val AMBIGUITY_HINT =
                "More than one on-screen node matches; retry with the intended node_id " +
                    "or a more specific selector (e.g. exact_match=true)"
        }
    }

/** Schema properties shared by click_node and tap_node: `node_id` OR selector (`by` + `value` [+ `exact_match`]). */
internal fun JsonObjectBuilder.putNodeTargetProperties(toolNamePrefix: String) {
    putJsonObject("node_id") {
        put("type", "string")
        put("description", "Node ID from ${toolNamePrefix}find_nodes. Mutually exclusive with 'by' + 'value'")
    }
    putJsonObject("by") {
        put("type", "string")
        put(
            "enum",
            buildJsonArray {
                add(JsonPrimitive("text"))
                add(JsonPrimitive("content_desc"))
                add(JsonPrimitive("resource_id"))
                add(JsonPrimitive("class_name"))
            },
        )
        put("description", "Selector criteria type; use with 'value' instead of 'node_id'")
    }
    putJsonObject("value") {
        put("type", "string")
        put("description", "Selector value; use with 'by'")
    }
    putJsonObject("exact_match") {
        put("type", "boolean")
        put("default", false)
        put("description", "Selector only. If true, match exactly. If false, match contains (case-insensitive)")
    }
}
