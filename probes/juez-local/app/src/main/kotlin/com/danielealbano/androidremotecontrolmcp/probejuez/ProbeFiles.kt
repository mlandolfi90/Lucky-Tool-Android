package com.danielealbano.androidremotecontrolmcp.probejuez

import android.content.Context
import android.content.Intent
import java.io.File

/** Model and result locations: app-specific external storage first (adb push), internal storage second (run-as). */
object ProbeFiles {
    fun modelDirs(context: Context): List<File> =
        listOfNotNull(
            context.getExternalFilesDir("models"),
            File(context.filesDir, "models").apply { mkdirs() },
        )

    fun resultsDir(context: Context): File =
        context.getExternalFilesDir("results") ?: File(context.filesDir, "results").apply { mkdirs() }

    fun findModel(
        context: Context,
        name: String,
    ): File? = modelDirs(context).map { File(it, name) }.firstOrNull { it.isFile }
}

/** One probe run, read from the launching intent's extras. */
data class RunRequest(
    val model: String,
    val backend: String,
    val delaySec: Int,
    val threads: Int,
    val tag: String,
    val thinking: String,
    val repeat: Int,
    val prompt: String,
) {
    companion object {
        const val EXTRA_MODEL = "model"
        const val EXTRA_BACKEND = "backend"
        const val EXTRA_DELAY = "delay"
        const val EXTRA_THREADS = "threads"
        const val EXTRA_TAG = "tag"
        const val EXTRA_THINKING = "thinking"
        const val EXTRA_REPEAT = "repeat"
        const val EXTRA_PROMPT = "prompt"
        const val THINKING_ON = "on"
        const val PROMPT_STEP = "paso"
        private const val DEFAULT_THREADS = 4

        fun from(intent: Intent): RunRequest? {
            val model = intent.getStringExtra(EXTRA_MODEL) ?: return null
            return RunRequest(
                model = model,
                backend = intent.getStringExtra(EXTRA_BACKEND) ?: "cpu",
                delaySec = intent.getIntExtra(EXTRA_DELAY, 0),
                threads = intent.getIntExtra(EXTRA_THREADS, DEFAULT_THREADS),
                tag = intent.getStringExtra(EXTRA_TAG) ?: "run",
                thinking = intent.getStringExtra(EXTRA_THINKING) ?: "config",
                repeat = intent.getIntExtra(EXTRA_REPEAT, 1),
                prompt = intent.getStringExtra(EXTRA_PROMPT) ?: "sino",
            )
        }
    }
}
