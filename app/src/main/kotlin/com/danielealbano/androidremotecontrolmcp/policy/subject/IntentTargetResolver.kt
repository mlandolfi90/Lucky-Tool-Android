package com.danielealbano.androidremotecontrolmcp.policy.subject

/** Delivery mode of `send_intent` (`open_uri` is always [ACTIVITY]). */
enum class IntentKind(
    val wireName: String,
) {
    ACTIVITY("activity"),
    BROADCAST("broadcast"),
    SERVICE("service"),
    ;

    companion object {
        fun fromWireName(value: String): IntentKind? = entries.firstOrNull { it.wireName == value }
    }
}

/** Installed packages that can receive an implicit intent (manifest components only). */
interface IntentTargetResolver {
    fun implicitPackages(
        kind: IntentKind,
        action: String?,
        data: String?,
        mimeType: String?,
    ): Set<String>
}
