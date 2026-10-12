package com.danielealbano.androidremotecontrolmcp.services.accessibility

/**
 * Output-shaping options for get_screen_state (Plan 67). The defaults reproduce the pre-Plan-67
 * output byte-for-byte.
 *
 * @property includeOffscreen When false, nodes with `visible == false` are not emitted (no TSV row,
 *   no hierarchy line); their emitted descendants are promoted like children of structural nodes.
 * @property includeHierarchy When false, no `hierarchy:` section is emitted for any window.
 */
data class CompactFormatOptions(
    val includeOffscreen: Boolean = true,
    val includeHierarchy: Boolean = true,
)
