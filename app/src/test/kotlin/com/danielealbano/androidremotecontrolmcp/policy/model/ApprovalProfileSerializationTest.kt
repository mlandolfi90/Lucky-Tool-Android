package com.danielealbano.androidremotecontrolmcp.policy.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ApprovalProfileSerializationTest {
    private val json = Json
    private val profile =
        ProfileTemplates
            .newProfile("com.example.bank", ProfileTemplate.MONEY_APP, 0L)
            .copy(
                maxAmount = "10000",
                amountTiers =
                    listOf(
                        AmountTier("100", RuleMode.ASK_IN_CHAT),
                        AmountTier("5000", RuleMode.NOTIFY),
                    ),
                notAmountFields =
                    listOf(PolicyException(PolicySelector(SelectorKind.RESOURCE_ID, "com.example.bank:id/iban"), 1L)),
                notActionButtons =
                    listOf(PolicyException(PolicySelector(SelectorKind.LABEL, "android.widget.Button|details"), 2L)),
            )

    @Test
    fun `profile round-trips through JSON`() {
        val encoded = json.encodeToString(ApprovalProfile.serializer(), profile)

        assertEquals(profile, json.decodeFromString(ApprovalProfile.serializer(), encoded))
    }

    @Test
    fun `enums are stored by name`() {
        val encoded = json.encodeToString(ApprovalProfile.serializer(), profile)

        assertTrue(encoded.contains("\"ASK_IN_CHAT\""))
        assertTrue(encoded.contains("\"ACTION_BUTTON\""))
        assertTrue(encoded.contains("\"RESOURCE_ID\""))
        assertFalse(encoded.contains("severity"))
        assertFalse(encoded.contains(":${RuleMode.FORBIDDEN.severity}"))
    }

    @Test
    fun `unknown rule mode fails the decode`() {
        val encoded = json.encodeToString(ApprovalProfile.serializer(), profile)
        val tampered = encoded.replace("\"TYPE\":\"ASK_IN_CHAT\"", "\"TYPE\":\"SOMETHING_NEW\"")

        assertTrue(tampered.contains("SOMETHING_NEW"))
        assertThrows<SerializationException> { json.decodeFromString(ApprovalProfile.serializer(), tampered) }
    }

    @Test
    fun `missing field fails the decode`() {
        val tree = json.parseToJsonElement(json.encodeToString(ApprovalProfile.serializer(), profile)).jsonObject
        val withoutRules = JsonObject(tree.filterKeys { it != "rules" })

        assertFalse(withoutRules.containsKey("rules"))
        assertEquals(JsonPrimitive("com.example.bank"), withoutRules["packageName"])
        assertThrows<SerializationException> {
            json.decodeFromString(ApprovalProfile.serializer(), withoutRules.toString())
        }
    }
}
