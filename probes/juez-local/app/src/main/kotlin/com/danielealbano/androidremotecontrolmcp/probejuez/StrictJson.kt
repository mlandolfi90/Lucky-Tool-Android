package com.danielealbano.androidremotecontrolmcp.probejuez

import org.json.JSONArray
import org.json.JSONObject

// Typed reads of the question file. Android's org.json turns a number into text on getString (the JVM library of
// the tests throws there), and both turn a text into a number on getInt; these accept only the written type, so the
// phone and the tests read a file the same way. (Duplicate keys: Android keeps the last one; run_probe.sh checks for
// them before pushing a file, and the JVM library fails on them in the tests.)

/** The text at [name]; a number or anything else fails. */
internal fun JSONObject.textAt(
    name: String,
    where: String,
): String {
    val value = get(name)
    require(value is String) { "$where: '$name' must be text" }
    return value
}

/** The text at [name], or null when it is missing, null or blank. */
internal fun JSONObject.textOrNullAt(
    name: String,
    where: String,
): String? = if (has(name) && !isNull(name)) textAt(name, where).takeIf { it.isNotBlank() } else null

/** The whole number at [name], or [default] when it is missing (no default: required); text or a fraction fails. */
internal fun JSONObject.intAt(
    name: String,
    where: String,
    default: Int? = null,
): Int {
    val value = if (has(name)) get(name) else requireNotNull(default) { "$where: '$name' is missing" }
    require(value is Int) { "$where: '$name' must be a whole number" }
    return value
}

/** The texts of [array], none of them blank and each one written as text; empty only when [allowEmpty]. */
internal fun textsOf(
    array: JSONArray,
    where: String,
    allowEmpty: Boolean = false,
): List<String> {
    val values = (0 until array.length()).map { array.get(it) }
    val texts = values.filterIsInstance<String>().filter { it.isNotBlank() }
    require(texts.size == values.size && (allowEmpty || texts.isNotEmpty())) {
        "$where: needs values${if (allowEmpty) "" else " (at least one)"}, each one text and none blank"
    }
    return texts
}

/** A misspelled key would otherwise be ignored and its default used without notice. */
internal fun JSONObject.requireKnownKeys(
    where: String,
    known: Set<String>,
) {
    val unknown = keys().asSequence().filterNot { it in known }.toList()
    require(unknown.isEmpty()) { "$where: unknown keys $unknown" }
}
