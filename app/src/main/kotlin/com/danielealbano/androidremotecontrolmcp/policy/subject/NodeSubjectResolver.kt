package com.danielealbano.androidremotecontrolmcp.policy.subject

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.mcp.tools.NodeSelectorResolver
import com.danielealbano.androidremotecontrolmcp.mcp.tools.NodeTarget
import com.danielealbano.androidremotecontrolmcp.mcp.tools.SelectorResolution
import com.danielealbano.androidremotecontrolmcp.policy.engine.OwnPackageName
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * Node tools: the target is the node the tool resolves (node_id, or selector among on-screen nodes) in a fresh read.
 * An ambiguous selector yields every on-screen candidate (strictest wins). tap_node adds the hit-test at the node
 * center (the tool taps a random point inside the node, possibly under an overlay).
 */
class NodeSubjectResolver
    @Inject
    constructor(
        private val windowSource: PolicyWindowSource,
        private val selectorResolver: NodeSelectorResolver,
        private val elementFinder: ElementFinder,
        private val hitTester: WindowHitTester,
        @param:OwnPackageName private val ownPackageName: String,
    ) : PolicySubjectResolver {
        override suspend fun resolve(
            policyClass: ToolPolicyClass,
            request: SubjectRequest,
        ): SubjectResolution =
            when (policyClass) {
                ToolPolicyClass.NODE_DETAILS_READ -> {
                    detailsRead(request)
                }

                ToolPolicyClass.NODE_CLICK -> {
                    val target = selectorResolver.parseTarget(request.arguments)
                    touch(target, TouchStyle.CLASSIFIED_ACTION, request, withTapPoint = false)
                }

                ToolPolicyClass.NODE_TAP -> {
                    val target = selectorResolver.parseTarget(request.arguments)
                    touch(target, TouchStyle.CLASSIFIED_ACTION, request, withTapPoint = true)
                }

                ToolPolicyClass.NODE_LONG_CLICK -> {
                    touch(parseNodeId(request.arguments), TouchStyle.CLASSIFIED_ACTION, request, withTapPoint = false)
                }

                ToolPolicyClass.NODE_SCROLL -> {
                    touch(parseNodeId(request.arguments), TouchStyle.NAVIGATION, request, withTapPoint = false)
                }

                else -> {
                    error("Unsupported policy class for NodeSubjectResolver: $policyClass")
                }
            }

        private fun parseNodeId(arguments: JsonObject?): NodeTarget {
            val nodeId =
                arguments?.get(PARAM_NODE_ID)?.jsonPrimitive?.contentOrNull
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'node_id'")
            if (nodeId.isEmpty()) throw McpToolException.InvalidParams("Parameter 'node_id' must be non-empty")
            return NodeTarget.ById(nodeId)
        }

        private fun touch(
            target: NodeTarget,
            style: TouchStyle,
            request: SubjectRequest,
            withTapPoint: Boolean,
        ): SubjectResolution {
            val result = windowSource.readWindows()
            if (result.degraded) return SubjectSupport.unattributed(style.actionType, request.profiledPackages)
            val nodes = locateAll(target, result.windows)
            val touched = if (withTapPoint) nodes + nodes.flatMap { tapPointHits(it, result.windows) } else nodes
            return SubjectSupport.touchSubject(result.windows, touched, style, request.profiledPackages, ownPackageName)
        }

        private fun detailsRead(request: SubjectRequest): SubjectResolution {
            val result = windowSource.readWindows()
            val located =
                if (result.degraded) {
                    emptyList()
                } else {
                    requestedIds(request.arguments).mapNotNull {
                        SubjectSupport.locate(elementFinder, result.windows, it)
                    }
                }
            return if (located.isEmpty()) {
                SubjectSupport.allWindowsRead(result, request.profiledPackages)
            } else {
                SubjectSupport.touchSubject(
                    result.windows,
                    located,
                    TouchStyle.READING,
                    request.profiledPackages,
                    ownPackageName,
                )
            }
        }

        private fun requestedIds(arguments: JsonObject?): List<String> =
            (arguments?.get(PARAM_NODE_IDS) as? JsonArray).orEmpty().mapNotNull { element ->
                (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            }

        private fun locateAll(
            target: NodeTarget,
            windows: List<WindowData>,
        ): List<NodeLocation> =
            when (target) {
                is NodeTarget.ById -> {
                    listOf(locateOrThrow(target.nodeId, windows))
                }

                is NodeTarget.BySelector -> {
                    when (val resolution = selectorResolver.resolve(windows, target.selector)) {
                        is SelectorResolution.Resolved -> {
                            listOf(locateOrThrow(resolution.element.id, windows))
                        }

                        is SelectorResolution.Ambiguous -> {
                            resolution.matches.filter { it.visible }.map { locateOrThrow(it.id, windows) }
                        }
                    }
                }
            }

        private fun locateOrThrow(
            nodeId: String,
            windows: List<WindowData>,
        ): NodeLocation =
            SubjectSupport.locate(elementFinder, windows, nodeId)
                ?: throw McpToolException.NodeNotFound("Node '$nodeId' not found in accessibility tree")

        private fun tapPointHits(
            location: NodeLocation,
            windows: List<WindowData>,
        ): List<NodeLocation> {
            val bounds = location.node.bounds
            return hitTester.hitTest(
                windows,
                (bounds.left + bounds.right) / CENTER_DIVISOR,
                (bounds.top + bounds.bottom) / CENTER_DIVISOR,
            )
        }

        companion object {
            private const val PARAM_NODE_ID = "node_id"
            private const val PARAM_NODE_IDS = "node_ids"
            private const val CENTER_DIVISOR = 2f
        }
    }
