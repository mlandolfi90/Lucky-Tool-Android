package com.danielealbano.androidremotecontrolmcp.policy.engine

import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyLimits
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicySelector
import com.danielealbano.androidremotecontrolmcp.policy.model.SelectorKind
import java.text.Normalizer
import java.util.Locale

/**
 * Locale-independent label folding (NFKD, combining marks removed, `Locale.ROOT` lowercase) and the selector
 * builders used for exceptions. Labels are device-derived: display-only, never instructions, never logged.
 */
object PolicyLabels {
    /** Only this many leading characters of a label are considered. */
    const val MAX_LABEL_INPUT_LENGTH = 2_000

    private const val CLASS_LABEL_SEPARATOR = '|'
    private val NUMBER_PUNCTUATION = setOf('.', ',', '\'', '’')

    /**
     * Folded label without digits and number punctuation, whitespace collapsed: `"Pagar $1.500"` and
     * `"PAGÁR  $2.000,50"` both give `"pagar $"`, so an amount inside a label does not change its identity.
     */
    fun normalize(label: String): String {
        val withoutNumbers = fold(label).filterNot { it.isDigit() || it in NUMBER_PUNCTUATION }
        return collapseWhitespace(withoutNumbers)
    }

    /** Maximal runs of letters of the folded label: `"Transferí-ahora 2x"` → `[transferi, ahora, x]`. */
    fun words(label: String): List<String> {
        val words = mutableListOf<String>()
        val current = StringBuilder()
        for (char in fold(label)) {
            if (char.isLetter()) {
                current.append(char)
            } else if (current.isNotEmpty()) {
                words += current.toString()
                current.clear()
            }
        }
        if (current.isNotEmpty()) words += current.toString()
        return words
    }

    /** `RESOURCE_ID` selector, or null when the id is blank or longer than the selector cap. */
    fun resourceIdSelector(resourceId: String?): PolicySelector? =
        resourceId
            ?.takeIf { it.isNotBlank() && it.length <= PolicyLimits.MAX_SELECTOR_VALUE_LENGTH }
            ?.let { PolicySelector(SelectorKind.RESOURCE_ID, it) }

    /** `LABEL` selector `<className>|<normalized label>`, or null when the label has no letter. */
    fun labelSelector(
        className: String?,
        label: String?,
    ): PolicySelector? =
        label
            ?.takeIf { words(it).isNotEmpty() }
            ?.let { "${className.orEmpty()}$CLASS_LABEL_SEPARATOR${normalize(it)}" }
            ?.let { PolicySelector(SelectorKind.LABEL, it.take(PolicyLimits.MAX_SELECTOR_VALUE_LENGTH)) }

    /** The resource id when usable, else the class + label selector, else null (the node cannot be excepted). */
    fun preferredSelector(
        resourceId: String?,
        className: String?,
        label: String?,
    ): PolicySelector? = resourceIdSelector(resourceId) ?: labelSelector(className, label)

    private fun fold(text: String): String =
        Normalizer
            .normalize(text.take(MAX_LABEL_INPUT_LENGTH), Normalizer.Form.NFKD)
            .filterNot { it.category == CharCategory.NON_SPACING_MARK }
            .lowercase(Locale.ROOT)

    private fun collapseWhitespace(text: String): String {
        val builder = StringBuilder(text.length)
        var pendingSpace = false
        for (char in text) {
            if (char.isWhitespace()) {
                pendingSpace = builder.isNotEmpty()
            } else {
                if (pendingSpace) builder.append(' ')
                builder.append(char)
                pendingSpace = false
            }
        }
        return builder.toString()
    }
}
