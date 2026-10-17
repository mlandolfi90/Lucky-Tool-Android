package com.danielealbano.androidremotecontrolmcp.policy

import java.util.Locale

/** Stable lower-case code of an enum constant for tool results and logs (`ACTION_BUTTON` → `action_button`). */
fun Enum<*>.wireCode(): String = name.lowercase(Locale.ROOT)
