package com.danielealbano.androidremotecontrolmcp.policy.model

import kotlinx.serialization.Serializable

private const val SEVERITY_RUN = 0
private const val SEVERITY_ASK_IN_CHAT = 1
private const val SEVERITY_NOTIFY = 2
private const val SEVERITY_FORBIDDEN = 9

/**
 * What a rule does with a matching call, ordered by [severity]: the strictest mode wins. Persisted by name;
 * [severity] is never persisted. The gap between [NOTIFY] and [FORBIDDEN] is reserved so later modes are added
 * without touching stored profiles.
 */
@Serializable
enum class RuleMode(
    val severity: Int,
) {
    RUN(SEVERITY_RUN),
    ASK_IN_CHAT(SEVERITY_ASK_IN_CHAT),
    NOTIFY(SEVERITY_NOTIFY),
    FORBIDDEN(SEVERITY_FORBIDDEN),
    ;

    fun isStricterThan(other: RuleMode): Boolean = severity > other.severity
}
