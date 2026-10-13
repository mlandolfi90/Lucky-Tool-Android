package com.danielealbano.androidremotecontrolmcp.policy.engine

import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.BANK_PACKAGE
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.NOTES_PACKAGE
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.OWN_PACKAGE
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.UNPROFILED_PACKAGE
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.commonProfile
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.moneyProfile
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.resourceId
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.snapshotOf
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.subject
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.target
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.tier
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType.ACTION_BUTTON
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType.NAVIGATE
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType.READ
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType.TYPE
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicySelector
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfilesSnapshot
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode.ASK_IN_CHAT
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode.FORBIDDEN
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode.NOTIFY
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode.RUN
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PolicyEngineImplTest {
    private val engine = PolicyFixtures.engine()
    private val bank = moneyProfile()
    private val tiered =
        commonProfile(
            maxAmount = "10000",
            amountTiers = listOf(tier("1000", ASK_IN_CHAT), tier("5000", NOTIFY)),
        )

    private fun assertDecision(
        decision: PolicyDecision,
        mode: RuleMode,
        reason: DecisionReason,
        packageName: String?,
        actionType: PolicyActionType,
    ) {
        assertEquals(PolicyDecision(mode, reason, packageName, actionType), decision)
    }

    private fun evaluate(
        subject: PolicySubject,
        snapshot: ProfilesSnapshot = snapshotOf(bank),
    ): PolicyDecision = engine.evaluate(subject, snapshot)

    private fun typeIn(
        amountText: String,
        snapshot: ProfilesSnapshot = snapshotOf(bank),
        target: PolicyTarget = target(),
    ): PolicyDecision = evaluate(subject(TYPE, listOf(target), amountText = amountText), snapshot)

    private fun tap(
        label: String?,
        snapshot: ProfilesSnapshot = snapshotOf(bank),
        selector: PolicySelector? = null,
        identifiable: Boolean = true,
    ): PolicyDecision =
        evaluate(
            subject(
                ACTION_BUTTON,
                listOf(target(label = label, selector = selector, identifiable = identifiable)),
                classifyTarget = true,
            ),
            snapshot,
        )

    @Test
    fun `no profile runs`() {
        val decision = evaluate(subject(TYPE, listOf(target(UNPROFILED_PACKAGE))), snapshotOf())

        assertDecision(decision, RUN, DecisionReason.NO_PROFILE, UNPROFILED_PACKAGE, TYPE)
    }

    @Test
    fun `own package is forbidden with zero profiles`() {
        listOf(TYPE, NAVIGATE, ACTION_BUTTON).forEach { type ->
            val decision = evaluate(subject(type, listOf(target(OWN_PACKAGE))), ProfilesSnapshot.EMPTY)

            assertDecision(decision, FORBIDDEN, DecisionReason.OWN_PACKAGE, OWN_PACKAGE, type)
        }
    }

    @Test
    fun `own package tap is forbidden even with a keyword-free label`() {
        val decision =
            evaluate(
                subject(ACTION_BUTTON, listOf(target(OWN_PACKAGE, label = "Ajustes")), classifyTarget = true),
                ProfilesSnapshot.EMPTY,
            )

        assertDecision(decision, FORBIDDEN, DecisionReason.OWN_PACKAGE, OWN_PACKAGE, ACTION_BUTTON)
    }

    @Test
    fun `own package read runs`() {
        val decision = evaluate(subject(READ, listOf(target(OWN_PACKAGE))), ProfilesSnapshot.EMPTY)

        assertDecision(decision, RUN, DecisionReason.NO_PROFILE, OWN_PACKAGE, READ)
    }

    @Test
    fun `own package wins among several targets`() {
        val decision =
            evaluate(
                subject(ACTION_BUTTON, listOf(target(UNPROFILED_PACKAGE), target(OWN_PACKAGE))),
                ProfilesSnapshot.EMPTY,
            )

        assertDecision(decision, FORBIDDEN, DecisionReason.OWN_PACKAGE, OWN_PACKAGE, ACTION_BUTTON)
    }

    @Test
    fun `unreadable profile forbids non-read`() {
        val snapshot = snapshotOf(unreadable = setOf(BANK_PACKAGE))

        assertDecision(
            evaluate(subject(TYPE), snapshot),
            FORBIDDEN,
            DecisionReason.PROFILE_UNREADABLE,
            BANK_PACKAGE,
            TYPE,
        )
        assertDecision(evaluate(subject(READ), snapshot), RUN, DecisionReason.PROFILE_UNREADABLE, BANK_PACKAGE, READ)
    }

    @Test
    fun `invalid stored profile is treated as unreadable`() {
        val snapshot = snapshotOf(bank.copy(rules = bank.rules - TYPE))

        assertDecision(
            evaluate(subject(NAVIGATE), snapshot),
            FORBIDDEN,
            DecisionReason.PROFILE_UNREADABLE,
            BANK_PACKAGE,
            NAVIGATE,
        )
    }

    @Test
    fun `money app rules apply`() {
        assertDecision(evaluate(subject(READ)), RUN, DecisionReason.RULE, BANK_PACKAGE, READ)
        assertDecision(evaluate(subject(NAVIGATE)), RUN, DecisionReason.RULE, BANK_PACKAGE, NAVIGATE)
        assertDecision(typeIn("hola"), ASK_IN_CHAT, DecisionReason.RULE, BANK_PACKAGE, TYPE)
        assertDecision(evaluate(subject(ACTION_BUTTON)), NOTIFY, DecisionReason.RULE, BANK_PACKAGE, ACTION_BUTTON)
    }

    @Test
    fun `forbidden rule`() {
        val snapshot = snapshotOf(bank.copy(rules = bank.rules + (TYPE to FORBIDDEN)))

        assertDecision(evaluate(subject(TYPE), snapshot), FORBIDDEN, DecisionReason.RULE, BANK_PACKAGE, TYPE)
    }

    @Test
    fun `strictest wins across targets`() {
        val decision = evaluate(subject(ACTION_BUTTON, listOf(target(UNPROFILED_PACKAGE), target(BANK_PACKAGE))))

        assertDecision(decision, NOTIFY, DecisionReason.RULE, BANK_PACKAGE, ACTION_BUTTON)
    }

    @Test
    fun `profiled reason wins a tie with an unprofiled target`() {
        val decision = evaluate(subject(READ, listOf(target(UNPROFILED_PACKAGE), target(BANK_PACKAGE))))

        assertDecision(decision, RUN, DecisionReason.RULE, BANK_PACKAGE, READ)
    }

    @Test
    fun `first target keeps its reason on equal severity`() {
        val notes = commonProfile().let { it.copy(rules = it.rules + (TYPE to FORBIDDEN)) }
        val decision =
            evaluate(
                subject(TYPE, listOf(target(BANK_PACKAGE), target(NOTES_PACKAGE)), amountText = "20000"),
                snapshotOf(bank, notes),
            )

        assertDecision(decision, FORBIDDEN, DecisionReason.AMOUNT_ABOVE_MAX, BANK_PACKAGE, TYPE)
    }

    @Test
    fun `amount tiers`() {
        val snapshot = snapshotOf(tiered)
        val notes = target(NOTES_PACKAGE)

        assertDecision(typeIn("500", snapshot, notes), ASK_IN_CHAT, DecisionReason.AMOUNT_TIER, NOTES_PACKAGE, TYPE)
        assertDecision(typeIn("1000", snapshot, notes), ASK_IN_CHAT, DecisionReason.AMOUNT_TIER, NOTES_PACKAGE, TYPE)
        assertDecision(typeIn("1001", snapshot, notes), NOTIFY, DecisionReason.AMOUNT_TIER, NOTES_PACKAGE, TYPE)
        assertDecision(typeIn("7000", snapshot, notes), NOTIFY, DecisionReason.AMOUNT_TIER, NOTES_PACKAGE, TYPE)
        assertDecision(typeIn("10000", snapshot, notes), NOTIFY, DecisionReason.AMOUNT_TIER, NOTES_PACKAGE, TYPE)
        assertDecision(
            typeIn("10000.01", snapshot, notes),
            FORBIDDEN,
            DecisionReason.AMOUNT_ABOVE_MAX,
            NOTES_PACKAGE,
            TYPE,
        )
    }

    @Test
    fun `largest number in the final value decides`() {
        val decision = typeIn("ref 12 monto 4.999", snapshotOf(tiered), target(NOTES_PACKAGE))

        assertDecision(decision, NOTIFY, DecisionReason.AMOUNT_TIER, NOTES_PACKAGE, TYPE)
    }

    @Test
    fun `ambiguous amount reads as the larger value`() {
        val decision = typeIn("1.500", snapshotOf(moneyProfile(maxAmount = "1000")))

        assertDecision(decision, FORBIDDEN, DecisionReason.AMOUNT_ABOVE_MAX, BANK_PACKAGE, TYPE)
    }

    @Test
    fun `stricter type rule beats a weaker tier`() {
        val decision = typeIn("500", snapshotOf(bank.copy(amountTiers = listOf(tier("1000", RUN)))))

        assertDecision(decision, ASK_IN_CHAT, DecisionReason.RULE, BANK_PACKAGE, TYPE)
    }

    @Test
    fun `amount above max is forbidden`() {
        assertDecision(typeIn("20000"), FORBIDDEN, DecisionReason.AMOUNT_ABOVE_MAX, BANK_PACKAGE, TYPE)
    }

    @Test
    fun `amount past parser caps is forbidden`() {
        assertDecision(typeIn("1e99"), FORBIDDEN, DecisionReason.AMOUNT_ABOVE_MAX, BANK_PACKAGE, TYPE)
        assertDecision(typeIn("x".repeat(10_001)), FORBIDDEN, DecisionReason.AMOUNT_ABOVE_MAX, BANK_PACKAGE, TYPE)
    }

    @Test
    fun `CBU typed in a money app is above max`() {
        val decision = typeIn("0170099220000067797370")

        assertDecision(decision, FORBIDDEN, DecisionReason.AMOUNT_ABOVE_MAX, BANK_PACKAGE, TYPE)
    }

    @Test
    fun `text without digits keeps the rule`() {
        assertDecision(typeIn("Juan Perez"), ASK_IN_CHAT, DecisionReason.RULE, BANK_PACKAGE, TYPE)
    }

    @Test
    fun `password field never counts`() {
        val decision = typeIn("999999", target = target(isPassword = true))

        assertDecision(decision, ASK_IN_CHAT, DecisionReason.RULE, BANK_PACKAGE, TYPE)
    }

    @Test
    fun `not-an-amount exception skips the amount check`() {
        val snapshot = snapshotOf(moneyProfile(notAmountFields = listOf(resourceId("acct"))))

        assertDecision(
            typeIn("99999999", snapshot, target(selector = resourceId("acct"))),
            ASK_IN_CHAT,
            DecisionReason.RULE,
            BANK_PACKAGE,
            TYPE,
        )
        assertDecision(
            typeIn("99999999", snapshot, target(selector = resourceId("amount"))),
            FORBIDDEN,
            DecisionReason.AMOUNT_ABOVE_MAX,
            BANK_PACKAGE,
            TYPE,
        )
    }

    @Test
    fun `no amount limit ignores numbers`() {
        val decision = typeIn("99999999", snapshotOf(commonProfile()), target(NOTES_PACKAGE))

        assertDecision(decision, RUN, DecisionReason.RULE, NOTES_PACKAGE, TYPE)
    }

    @Test
    fun `amount applies to non-type actions`() {
        assertDecision(
            evaluate(subject(ACTION_BUTTON, amountText = "20000")),
            FORBIDDEN,
            DecisionReason.AMOUNT_ABOVE_MAX,
            BANK_PACKAGE,
            ACTION_BUTTON,
        )
        assertDecision(
            evaluate(subject(NAVIGATE, amountText = "500")),
            RUN,
            DecisionReason.RULE,
            BANK_PACKAGE,
            NAVIGATE,
        )
    }

    @Test
    fun `keyword makes a tap an action button`() {
        assertDecision(tap("Pagar"), NOTIFY, DecisionReason.ACTION_KEYWORD, BANK_PACKAGE, ACTION_BUTTON)
    }

    @Test
    fun `keyword beats a not-an-action-button exception`() {
        val snapshot = snapshotOf(moneyProfile(notActionButtons = listOf(resourceId("btn"))))

        assertDecision(
            tap("Confirmar", snapshot, resourceId("btn")),
            NOTIFY,
            DecisionReason.ACTION_KEYWORD,
            BANK_PACKAGE,
            ACTION_BUTTON,
        )
    }

    @Test
    fun `labeled non-keyword tap is navigation`() {
        assertDecision(tap("Movimientos"), RUN, DecisionReason.RULE, BANK_PACKAGE, NAVIGATE)
    }

    @Test
    fun `unidentifiable tap is an action button`() {
        assertDecision(
            tap("Movimientos", identifiable = false),
            NOTIFY,
            DecisionReason.UNKNOWN_TARGET,
            BANK_PACKAGE,
            ACTION_BUTTON,
        )
    }

    @Test
    fun `unlabeled tap is an action button`() {
        assertDecision(
            tap(null, selector = resourceId("icon")),
            NOTIFY,
            DecisionReason.UNKNOWN_TARGET,
            BANK_PACKAGE,
            ACTION_BUTTON,
        )
    }

    @Test
    fun `label without letters is unknown`() {
        assertDecision(tap("$ 1.500"), NOTIFY, DecisionReason.UNKNOWN_TARGET, BANK_PACKAGE, ACTION_BUTTON)
    }

    @Test
    fun `not-an-action-button exception downgrades an unlabeled target`() {
        val snapshot = snapshotOf(moneyProfile(notActionButtons = listOf(resourceId("icon"))))

        assertDecision(tap(null, snapshot, resourceId("icon")), RUN, DecisionReason.RULE, BANK_PACKAGE, NAVIGATE)
    }

    @Test
    fun `unclassified action button stays an action button`() {
        val decision = evaluate(subject(ACTION_BUTTON, listOf(target(label = "Movimientos"))))

        assertDecision(decision, NOTIFY, DecisionReason.RULE, BANK_PACKAGE, ACTION_BUTTON)
    }

    @Test
    fun `subject without targets`() {
        assertDecision(
            evaluate(subject(TYPE, emptyList())),
            FORBIDDEN,
            DecisionReason.UNKNOWN_TARGET,
            null,
            TYPE,
        )
        assertDecision(
            evaluate(subject(TYPE, emptyList()), ProfilesSnapshot.EMPTY),
            RUN,
            DecisionReason.NO_PROFILE,
            null,
            TYPE,
        )
        assertDecision(evaluate(subject(READ, emptyList())), RUN, DecisionReason.NO_PROFILE, null, READ)
    }

    @Test
    fun `unreadable-only snapshot still fails closed without targets`() {
        val decision = evaluate(subject(NAVIGATE, emptyList()), snapshotOf(unreadable = setOf(BANK_PACKAGE)))

        assertDecision(decision, FORBIDDEN, DecisionReason.UNKNOWN_TARGET, null, NAVIGATE)
    }
}
