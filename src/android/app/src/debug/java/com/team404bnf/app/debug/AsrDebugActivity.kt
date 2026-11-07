package com.team404bnf.app.debug

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.team404bnf.app.localml.AudioToMono16k
import com.team404bnf.ml.ModelManager
import com.team404bnf.ml.OnnxSessionManager
import com.team404bnf.ml.asr.GigaamAsrEngine
import com.team404bnf.ml.asr.Pcm16WavReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Debug-only local ASR inside the existing APK. Never uploads user audio. */
class AsrDebugActivity : ComponentActivity() {
    private lateinit var logView: TextView
    private lateinit var pick: Button
    private val wavPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        pick.isEnabled = false
        lifecycleScope.launch {
            val sourceFile = java.io.File.createTempFile("debug_src", ".audio", cacheDir)
            val pcmFile = java.io.File.createTempFile("debug_pcm", ".pcm", cacheDir)
            try {
                val totalSamples = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input: java.io.InputStream ->
                        java.io.FileOutputStream(sourceFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    AudioToMono16k.decode(sourceFile, pcmFile)
                }
                log("PCM16 WAV loaded: %.2f seconds".format(totalSamples / 16000.0))
                val startNs = System.nanoTime()
                val manager = ModelManager(applicationContext)
                val engine = GigaamAsrEngine(manager, OnnxSessionManager(manager))
                val result = engine.recognize(pcmFile, totalSamples) { stage -> log(stage) }
                val elapsed = (System.nanoTime() - startNs) / 1e9
                log("✅ GigaAM completed in %.1fs; words=%d".format(elapsed, result.words.size))
                log("TEXT: ${result.text}")
                result.words.take(40).forEach { w ->
                    log("[%.3f–%.3f] %s".format(w.startSeconds, w.endSeconds, w.text))
                }
                if (result.words.size > 40) log("… first 40 words shown")
            } catch (t: Throwable) {
                log("❌ ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                sourceFile.delete()
                pcmFile.delete()
                pick.isEnabled = true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        layout.addView(TextView(this).apply {
            text = "404BNF M2: GigaAM v3 E2E RNNT FP32\nПолностью локально. Для первой проверки: WAV PCM16 / mono / 16000 Hz. Сначала установи модели через M1."
            textSize = 17f
        })
        pick = Button(this).apply { text = "Выбрать WAV и распознать локально" }
        pick.setOnClickListener { wavPicker.launch(arrayOf("audio/*", "application/octet-stream")) }
        layout.addView(pick)
        val scroll = ScrollView(this)
        logView = TextView(this).apply { text = "Ожидание WAV…\n"; textSize = 13f }
        scroll.addView(logView)
        layout.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(layout)
    }
    private fun log(line: String) {
        runOnUiThread { logView.append(line + "\n") }
    }
}
