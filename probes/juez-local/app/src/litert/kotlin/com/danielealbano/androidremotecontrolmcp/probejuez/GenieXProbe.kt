package com.danielealbano.androidremotecontrolmcp.probejuez

import android.content.Context

/** The litert build carries no GenieX runtime: the `geniex` backend needs the geniex build of the probe. */
object GenieXProbe {
    fun run(
        context: Context,
        request: RunRequest,
    ): Unit = error("backend ${request.backend} needs the geniex build (${context.packageName} is the litert build)")
}
