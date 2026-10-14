package com.danielealbano.androidremotecontrolmcp.probejuez

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

/** Shows where the models must be pushed and forwards a run request (intent extras) to [ProbeService]. */
class ProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text =
            TextView(this).apply {
                setPadding(PADDING_PX, PADDING_PX, PADDING_PX, PADDING_PX)
                textSize = TEXT_SIZE_SP
            }
        setContentView(ScrollView(this).apply { addView(text) })
        text.text =
            buildString {
                appendLine("Carpetas de modelos:")
                ProbeFiles.modelDirs(this@ProbeActivity).forEach { dir ->
                    appendLine("${dir.absolutePath}: ${dir.list()?.joinToString().orEmpty()}")
                }
                val images = ProbeFiles.imagesDir(this@ProbeActivity)
                appendLine("Imágenes: ${images.absolutePath}: ${images.list()?.joinToString().orEmpty()}")
                appendLine("Resultados: ${ProbeFiles.resultsDir(this@ProbeActivity).absolutePath}")
            }
        val model = intent.getStringExtra(RunRequest.EXTRA_MODEL)
        if (model != null) {
            startForegroundService(Intent(this, ProbeService::class.java).putExtras(intent))
            text.append("\nCorrida pedida: $model")
        }
    }

    private companion object {
        const val PADDING_PX = 32
        const val TEXT_SIZE_SP = 14f
    }
}
