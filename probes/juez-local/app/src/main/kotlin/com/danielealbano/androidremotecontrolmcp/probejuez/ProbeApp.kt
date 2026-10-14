package com.danielealbano.androidremotecontrolmcp.probejuez

import android.app.Application
import android.system.Os

/**
 * Points the Hexagon DSP loader at the app's extracted native libraries before any engine starts: the NPU
 * backend reads ADSP_LIBRARY_PATH once per process, so it must be set here and not later.
 */
class ProbeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val libraryDir = applicationInfo.nativeLibraryDir
        Os.setenv("ADSP_LIBRARY_PATH", libraryDir, true)
        Os.setenv("LD_LIBRARY_PATH", libraryDir, true)
    }
}
