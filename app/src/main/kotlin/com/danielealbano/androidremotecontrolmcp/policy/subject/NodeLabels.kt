package com.danielealbano.androidremotecontrolmcp.policy.subject

import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData

/**
 * Raw labels of touched nodes. Device-derived: used for keyword matching, selectors and display in the owner's
 * approval UI only; NEVER logged or returned to the agent. An editable node's value never counts as a label.
 */
internal object NodeLabels {
    private const val MAX_LABEL_NODES = 25
    private const val MAX_LABEL_LENGTH = 200
    private const val PART_SEPARATOR = " "
    private val WHITESPACE = Regex("\\s+")

    /** Tap/click label: own + descendants' text (breadth-first, capped); an editable node uses [fieldLabel]. */
    fun interactiveLabel(node: AccessibilityNodeData): String? =
        if (node.editable) {
            fieldLabel(node)
        } else {
            subtreeLabel(node)
        }

    /** Field label: hint, labelled-by text and description, never the value being edited. */
    fun fieldLabel(node: AccessibilityNodeData): String? = join(fieldParts(node))

    private fun subtreeLabel(node: AccessibilityNodeData): String? {
        val visited = mutableListOf(node)
        var index = 0
        while (index < visited.size) {
            val room = MAX_LABEL_NODES - visited.size
            if (room > 0) visited.addAll(visited[index].children.take(room))
            index++
        }
        return join(visited.flatMap(::partsOf))
    }

    private fun partsOf(node: AccessibilityNodeData): List<String> =
        if (node.editable) {
            fieldParts(node)
        } else {
            distinctParts(listOfNotNull(node.text, node.contentDescription, node.hintText, node.labeledByText))
        }

    private fun fieldParts(node: AccessibilityNodeData): List<String> =
        distinctParts(listOfNotNull(node.hintText, node.labeledByText, node.contentDescription))

    /** One node's parts, each once (case and spacing ignored): a button with text and description "7" reads "7". */
    private fun distinctParts(parts: List<String>): List<String> =
        parts.filter { it.isNotBlank() }.distinctBy { WHITESPACE.replace(it.trim(), PART_SEPARATOR).lowercase() }

    private fun join(parts: List<String>): String? =
        WHITESPACE
            .replace(parts.filter { it.isNotBlank() }.joinToString(PART_SEPARATOR), PART_SEPARATOR)
            .trim()
            .take(MAX_LABEL_LENGTH)
            .ifEmpty { null }
}
