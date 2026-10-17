package com.danielealbano.androidremotecontrolmcp.policy.model

import kotlinx.serialization.Serializable

/**
 * Amounts up to and including [upTo] get [mode]. [upTo] is a plain decimal string (`AmountLimits.canonical`):
 * no currency, at most `AmountLimits.MAX_DECIMALS` decimals. [toString] omits [upTo] (an amount limit).
 */
@Serializable
data class AmountTier(
    val upTo: String,
    val mode: RuleMode,
) {
    override fun toString(): String = "AmountTier(mode=$mode)"
}
