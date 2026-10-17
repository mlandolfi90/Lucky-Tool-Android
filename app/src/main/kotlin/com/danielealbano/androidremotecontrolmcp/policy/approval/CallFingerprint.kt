package com.danielealbano.androidremotecontrolmcp.policy.approval

import com.danielealbano.androidremotecontrolmcp.policy.model.PolicySelector
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.util.Locale

/**
 * SHA-256 binding of one tool call: tool name + canonical JSON arguments + target package + selector. Every field is
 * length-prefixed (null has its own marker), so field boundaries cannot be shifted to forge a collision.
 */
object CallFingerprint {
    private const val HASH_ALGORITHM = "SHA-256"
    private const val MAX_CANONICAL_DEPTH = 64
    private const val NULL_FIELD = "-;"

    /** Lowercase 64-hex fingerprint. */
    fun of(
        toolName: String,
        arguments: JsonObject?,
        packageName: String?,
        selector: PolicySelector?,
    ): String {
        val digest = MessageDigest.getInstance(HASH_ALGORITHM)
        listOf(
            toolName,
            arguments?.let { canonicalJson(it) },
            packageName,
            selector?.kind?.name,
            selector?.value,
        ).forEach { field -> digest.update(encodeField(field)) }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    /**
     * Object keys sorted at every depth, array order kept, primitives in their JSON form. Beyond
     * [MAX_CANONICAL_DEPTH] the subtree is appended as received (still deterministic for the same input).
     */
    fun canonicalJson(element: JsonElement): String {
        val out = StringBuilder()
        appendCanonical(out, element, 0)
        return out.toString()
    }

    private fun encodeField(field: String?): ByteArray =
        (if (field == null) NULL_FIELD else "${field.length}:$field;").toByteArray(Charsets.UTF_8)

    private fun appendCanonical(
        out: StringBuilder,
        element: JsonElement,
        depth: Int,
    ) {
        when {
            depth >= MAX_CANONICAL_DEPTH -> out.append(element.toString())
            element is JsonObject -> appendObject(out, element, depth)
            element is JsonArray -> appendArray(out, element, depth)
            else -> out.append(element.toString())
        }
    }

    private fun appendObject(
        out: StringBuilder,
        obj: JsonObject,
        depth: Int,
    ) {
        out.append('{')
        obj.entries.sortedBy { it.key }.forEachIndexed { index, (key, value) ->
            if (index > 0) out.append(',')
            out.append(JsonPrimitive(key).toString()).append(':')
            appendCanonical(out, value, depth + 1)
        }
        out.append('}')
    }

    private fun appendArray(
        out: StringBuilder,
        array: JsonArray,
        depth: Int,
    ) {
        out.append('[')
        array.forEachIndexed { index, value ->
            if (index > 0) out.append(',')
            appendCanonical(out, value, depth + 1)
        }
        out.append(']')
    }
}
