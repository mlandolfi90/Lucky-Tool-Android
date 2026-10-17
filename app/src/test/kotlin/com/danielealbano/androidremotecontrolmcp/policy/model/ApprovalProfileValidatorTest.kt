package com.danielealbano.androidremotecontrolmcp.policy.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApprovalProfileValidatorTest {
    private val money =
        ProfileTemplates.newProfile("com.example.bank", ProfileTemplate.MONEY_APP, 0L).copy(maxAmount = "10000")
    private val common = ProfileTemplates.newProfile("com.example.notes", ProfileTemplate.COMMON_APP, 0L)

    private fun errors(profile: ApprovalProfile) = ApprovalProfileValidator.validate(profile)

    private fun tier(
        upTo: String,
        mode: RuleMode,
    ) = AmountTier(upTo, mode)

    private fun exceptions(
        count: Int,
        value: String = "com.example.bank:id/field",
    ): List<PolicyException> =
        List(count) { index ->
            PolicyException(PolicySelector(SelectorKind.RESOURCE_ID, "$value$index"), 0L)
        }

    @Test
    fun `valid money profile has no errors`() {
        assertTrue(errors(money).isEmpty())
    }

    @Test
    fun `valid common profile without limit has no errors`() {
        assertTrue(errors(common).isEmpty())
    }

    @Test
    fun `money profile without maxAmount`() {
        assertEquals(listOf(ProfileValidationError.MAX_AMOUNT_REQUIRED), errors(money.copy(maxAmount = null)))
    }

    @Test
    fun `common profile without maxAmount`() {
        assertTrue(errors(common.copy(maxAmount = null)).isEmpty())
    }

    @Test
    fun `eight-decimal limits are valid`() {
        val profile =
            money.copy(maxAmount = "0.5", amountTiers = listOf(AmountTier("0.00000001", RuleMode.ASK_IN_CHAT)))

        assertTrue(errors(profile).isEmpty())
    }

    @Test
    fun `invalid package names`() {
        listOf("bank", "com..bank", "1com.bank", "com.bank-x", "com.bank.", "", "a." + "b".repeat(254)).forEach {
            assertTrue(ProfileValidationError.PACKAGE_NAME_INVALID in errors(common.copy(packageName = it)), it)
        }
    }

    @Test
    fun `valid package names`() {
        listOf("com.example.bank_2", "a.b").forEach {
            assertFalse(ProfileValidationError.PACKAGE_NAME_INVALID in errors(common.copy(packageName = it)), it)
        }
    }

    @Test
    fun `missing rule`() {
        val profile = common.copy(rules = common.rules - PolicyActionType.TYPE)

        assertTrue(ProfileValidationError.RULES_INCOMPLETE in errors(profile))
    }

    @Test
    fun `invalid maxAmount`() {
        listOf("abc", "-5", "0", "1e3", "1,5", "1.123456789", "").forEach {
            val result = errors(money.copy(maxAmount = it))

            assertTrue(ProfileValidationError.MAX_AMOUNT_INVALID in result, it)
            assertFalse(ProfileValidationError.MAX_AMOUNT_REQUIRED in result, it)
        }
    }

    @Test
    fun `tiers without maxAmount`() {
        val profile = common.copy(maxAmount = null, amountTiers = listOf(AmountTier("100", RuleMode.ASK_IN_CHAT)))

        assertTrue(ProfileValidationError.TIERS_REQUIRE_MAX_AMOUNT in errors(profile))
    }

    @Test
    fun `too many tiers`() {
        val tiers = (1..6).map { AmountTier((it * 100).toString(), RuleMode.ASK_IN_CHAT) }

        assertEquals(listOf(ProfileValidationError.TOO_MANY_TIERS), errors(money.copy(amountTiers = tiers)))
    }

    @Test
    fun `unparseable tier amount`() {
        val profile = money.copy(amountTiers = listOf(AmountTier("x", RuleMode.ASK_IN_CHAT)))

        assertTrue(ProfileValidationError.TIER_AMOUNT_INVALID in errors(profile))
    }

    @Test
    fun `tiers must be strictly ascending`() {
        listOf(listOf("500", "500"), listOf("1000", "500")).forEach { limits ->
            val profile = money.copy(amountTiers = limits.map { AmountTier(it, RuleMode.ASK_IN_CHAT) })

            assertTrue(ProfileValidationError.TIERS_NOT_ASCENDING in errors(profile), limits.toString())
        }
    }

    @Test
    fun `tier above maxAmount`() {
        val above = money.copy(amountTiers = listOf(AmountTier("20000", RuleMode.ASK_IN_CHAT)))
        val equal = money.copy(amountTiers = listOf(AmountTier("10000", RuleMode.ASK_IN_CHAT)))

        assertTrue(ProfileValidationError.TIER_ABOVE_MAX_AMOUNT in errors(above))
        assertFalse(ProfileValidationError.TIER_ABOVE_MAX_AMOUNT in errors(equal))
    }

    @Test
    fun `tier modes must not decrease`() {
        val decreasing =
            money.copy(amountTiers = listOf(tier("1000", RuleMode.NOTIFY), tier("5000", RuleMode.ASK_IN_CHAT)))
        val equalModes = money.copy(amountTiers = listOf(tier("1000", RuleMode.NOTIFY), tier("5000", RuleMode.NOTIFY)))

        assertTrue(ProfileValidationError.TIER_MODES_DECREASING in errors(decreasing))
        assertFalse(ProfileValidationError.TIER_MODES_DECREASING in errors(equalModes))
    }

    @Test
    fun `too many exceptions`() {
        val limit = PolicyLimits.MAX_EXCEPTIONS_PER_LIST
        val tooMany = ProfileValidationError.TOO_MANY_EXCEPTIONS

        assertTrue(tooMany in errors(money.copy(notAmountFields = exceptions(limit + 1))))
        assertFalse(tooMany in errors(money.copy(notAmountFields = exceptions(limit))))
        assertTrue(tooMany in errors(money.copy(notActionButtons = exceptions(limit + 1))))
        assertFalse(tooMany in errors(money.copy(notActionButtons = exceptions(limit))))
    }

    @Test
    fun `invalid exception selector`() {
        listOf(" ", "x".repeat(PolicyLimits.MAX_SELECTOR_VALUE_LENGTH + 1)).forEach { value ->
            val exception = PolicyException(PolicySelector(SelectorKind.LABEL, value), 0L)

            val result = errors(money.copy(notActionButtons = listOf(exception)))

            assertTrue(ProfileValidationError.EXCEPTION_SELECTOR_INVALID in result)
        }
    }

    @Test
    fun `errors are distinct and in declaration order`() {
        val profile =
            money.copy(
                packageName = "x",
                maxAmount = "abc",
                amountTiers = listOf(AmountTier("x", RuleMode.NOTIFY), AmountTier("5", RuleMode.RUN)),
            )

        assertEquals(
            listOf(
                ProfileValidationError.PACKAGE_NAME_INVALID,
                ProfileValidationError.MAX_AMOUNT_INVALID,
                ProfileValidationError.TIER_AMOUNT_INVALID,
                ProfileValidationError.TIER_MODES_DECREASING,
            ),
            errors(profile),
        )
    }
}
