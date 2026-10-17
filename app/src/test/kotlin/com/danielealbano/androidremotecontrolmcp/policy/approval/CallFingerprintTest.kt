package com.danielealbano.androidremotecontrolmcp.policy.approval

import com.danielealbano.androidremotecontrolmcp.policy.model.PolicySelector
import com.danielealbano.androidremotecontrolmcp.policy.model.SelectorKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CallFingerprintTest {
    private val selector = PolicySelector(SelectorKind.RESOURCE_ID, "com.example.bank:id/pay")
    private val arguments = buildJsonObject { put("x", 1) }

    private fun fingerprint(
        toolName: String = "tap",
        arguments: JsonObject? = this.arguments,
        packageName: String? = "com.example.bank",
        selector: PolicySelector? = this.selector,
    ): String = CallFingerprint.of(toolName, arguments, packageName, selector)

    @Test
    fun `fingerprint is 64 lowercase hex`() {
        assertTrue(Regex("^[0-9a-f]{64}$").matches(fingerprint()))
    }

    @Test
    fun `top-level key order does not matter`() {
        val xy =
            buildJsonObject {
                put("x", 1)
                put("y", 2)
            }
        val yx =
            buildJsonObject {
                put("y", 2)
                put("x", 1)
            }

        assertEquals(fingerprint(arguments = xy), fingerprint(arguments = yx))
    }

    @Test
    fun `nested key order does not matter`() {
        val first =
            buildJsonObject {
                putJsonArray("items") {
                    addJsonObject {
                        put("a", 1)
                        put("b", "two")
                    }
                }
            }
        val second =
            buildJsonObject {
                putJsonArray("items") {
                    addJsonObject {
                        put("b", "two")
                        put("a", 1)
                    }
                }
            }

        assertEquals(fingerprint(arguments = first), fingerprint(arguments = second))
    }

    @Test
    fun `array order matters`() {
        val ascending = buildJsonObject { put("v", JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2)))) }
        val descending = buildJsonObject { put("v", JsonArray(listOf(JsonPrimitive(2), JsonPrimitive(1)))) }

        assertNotEquals(fingerprint(arguments = ascending), fingerprint(arguments = descending))
    }

    @Test
    fun `each field changes the fingerprint`() {
        val variants =
            listOf(
                fingerprint(),
                fingerprint(toolName = "click_node"),
                fingerprint(packageName = "com.example.notes"),
                fingerprint(selector = selector.copy(kind = SelectorKind.LABEL)),
                fingerprint(selector = selector.copy(value = "com.example.bank:id/other")),
                fingerprint(arguments = buildJsonObject { put("x", 2) }),
            )

        assertEquals(variants.size, variants.toSet().size)
    }

    @Test
    fun `null and empty arguments differ`() {
        assertNotEquals(fingerprint(arguments = null), fingerprint(arguments = JsonObject(emptyMap())))
    }

    @Test
    fun `field boundaries cannot shift`() {
        assertNotEquals(
            fingerprint(toolName = "ab", packageName = "c"),
            fingerprint(toolName = "a", packageName = "bc"),
        )
    }

    @Test
    fun `number spelling is kept`() {
        val integer = JsonObject(mapOf("x" to JsonPrimitive(1)))
        val decimal = JsonObject(mapOf("x" to JsonPrimitive(1.0)))

        assertNotEquals(fingerprint(arguments = integer), fingerprint(arguments = decimal))
    }

    @Test
    fun `canonicalJson output is exact`() {
        val input =
            buildJsonObject {
                putJsonArray("b") {
                    addJsonObject {
                        put("d", 1)
                        put("c", "x")
                    }
                }
                put("a", JsonNull)
            }

        assertEquals("""{"a":null,"b":[{"c":"x","d":1}]}""", CallFingerprint.canonicalJson(input))
    }

    @Test
    fun `keys are JSON-escaped`() {
        val input = buildJsonObject { put("a\"b", 1) }

        assertEquals("""{"a\"b":1}""", CallFingerprint.canonicalJson(input))
    }

    @Test
    fun `depth beyond the cap does not throw`() {
        var nested: JsonElement = JsonPrimitive(1)
        repeat(DEEP_NESTING) { nested = JsonArray(listOf(nested)) }
        val deep = JsonObject(mapOf("deep" to nested))

        val first = fingerprint(arguments = deep)

        assertTrue(Regex("^[0-9a-f]{64}$").matches(first))
        assertEquals(first, fingerprint(arguments = deep))
    }

    private companion object {
        const val DEEP_NESTING = 200
    }
}
