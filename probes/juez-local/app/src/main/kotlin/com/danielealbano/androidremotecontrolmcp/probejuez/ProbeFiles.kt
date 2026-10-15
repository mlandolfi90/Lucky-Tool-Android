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

    fun imagesDir(context: Context): File =
        context.getExternalFilesDir("images") ?: File(context.filesDir, "images").apply { mkdirs() }

    /** The question files ([JudgeConfig]), pushed before every run so that the questions change without a build. */
    fun configDir(context: Context): File =
        context.getExternalFilesDir("config") ?: File(context.filesDir, "config").apply { mkdirs() }

    fun findModel(
        context: Context,
        name: String,
    ): File? = modelDirs(context).map { File(it, name) }.firstOrNull { it.isFile }

    fun findConfig(
        context: Context,
        name: String,
    ): File? = File(configDir(context), name).takeIf { it.isFile }
}

/**
 * One probe run, read from the launching intent's extras. [speculativeMode] and [benchMode] keep the words as
 * given ([speculative] and [benchmark] read them); [visualBudget] null leaves the image token budget of the model.
 */
data class RunRequest(
    val model: String,
    val backend: String,
    val delaySec: Int,
    val threads: Int,
    val tag: String,
    val thinking: String,
    val repeat: Int,
    val prompt: String,
    val set: String,
    val sampler: String,
    val casesFile: String,
    val speculativeMode: String,
    val visualBudget: Int?,
    val benchMode: String,
) {
    /** null leaves the model's own setting; an unknown word fails the run instead of falling back without notice. */
    val speculative: Boolean?
        get() =
            when (speculativeMode) {
                DEFAULT -> null
                ON -> true
                OFF -> false
                else -> error("speculative '$speculativeMode' is not $DEFAULT, $ON or $OFF")
            }

    val benchmark: Boolean
        get() =
            when (benchMode) {
                ON -> true
                OFF -> false
                else -> error("bench '$benchMode' is not $ON or $OFF")
            }

    companion object {
        const val EXTRA_MODEL = "model"
        const val EXTRA_BACKEND = "backend"
        const val EXTRA_DELAY = "delay"
        const val EXTRA_THREADS = "threads"
        const val EXTRA_TAG = "tag"
        const val EXTRA_THINKING = "thinking"
        const val EXTRA_REPEAT = "repeat"
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_SET = "set"
        const val EXTRA_SAMPLER = "sampler"
        const val EXTRA_CASES_FILE = "cases"
        const val EXTRA_SPECULATIVE = "speculative"
        const val EXTRA_VISUAL_BUDGET = "visual"
        const val EXTRA_BENCHMARK = "bench"
        const val THINKING_ON = "on"
        const val SET_TEXT = "texto"
        const val SET_IMAGES = "imagenes"
        const val SET_ALL = "todo"
        const val SAMPLER_NONE = "none"
        const val DEFAULT_CASES_FILE = "casos-v1.json"
        private const val DEFAULT = "default"
        private const val ON = "on"
        private const val OFF = "off"
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
                set = intent.getStringExtra(EXTRA_SET) ?: SET_TEXT,
                sampler = intent.getStringExtra(EXTRA_SAMPLER) ?: "greedy",
                casesFile = intent.getStringExtra(EXTRA_CASES_FILE) ?: DEFAULT_CASES_FILE,
                speculativeMode = intent.getStringExtra(EXTRA_SPECULATIVE) ?: DEFAULT,
                visualBudget = intent.getIntExtra(EXTRA_VISUAL_BUDGET, 0).takeIf { it > 0 },
                benchMode = intent.getStringExtra(EXTRA_BENCHMARK) ?: OFF,
            )
        }
    }
}
