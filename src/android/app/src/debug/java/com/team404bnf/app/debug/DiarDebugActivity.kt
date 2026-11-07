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
import com.team404bnf.ml.ModelManager
import com.team404bnf.ml.OnnxSessionManager
import com.team404bnf.ml.asr.GigaamAsrEngine
import com.team404bnf.ml.asr.Pcm16WavReader
import com.team404bnf.ml.diar.DiarTurn
import com.team404bnf.ml.diar.WespeakerDiarizationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Debug entry point, no network: read WAV -> optionally M2 ASR -> M3 diarization. */
class DiarDebugActivity : ComponentActivity() {
    private lateinit var output: TextView
    private lateinit var diarButton: Button
    private lateinit var fullButton: Button
    private var full = false

    private val wavPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        diarButton.isEnabled = false
        fullButton.isEnabled = false
        output.text = ""
        lifecycleScope.launch {
            try {
                val audio = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { Pcm16WavReader.read(it, 120) }
                        ?: error("Cannot read WAV")
                }
                log("WAV: %.2f seconds (mono PCM16 16 kHz)".format(Locale.ROOT, audio.size / 16000.0))
                val manager = ModelManager(applicationContext)
                val sessions = OnnxSessionManager(manager)
                // M2 runs independently and completes before M3 uses any sessions.
                val asr = if (full) {
                    log("Running M2 GigaAM FP32 ASR first...")
                    GigaamAsrEngine(manager, sessions).recognize(audio) { log(it) }.also {
                        log("M2: words=${it.words.size}; TEXT=${it.text}")
                    }
                } else null
                log("Running M3 Silero + WeSpeaker...")
                val result = WespeakerDiarizationEngine(sessions).diarize(audio) { log(it) }
                log("M3 DONE: speakers=${result.speakersDetected}; VAD=${result.speechIntervals}; " +
                    "embeddings=${result.embeddingWindows}; intervals=${result.turns.size}; " +
                    "elapsed=%.1fs".format(Locale.ROOT, result.elapsedSeconds))
                result.turns.take(100).forEach { t ->
                    log("[%.3f–%.3f] %s".format(Locale.ROOT, t.start, t.end, t.speakerId))
                }
                if (result.turns.size > 100) log("... showing first 100 intervals")
                if (asr != null) {
                    log("---- M2 + M3 speaker preview (NOT the final JSON v1) ----")
                    asr.words.take(100).forEach { word ->
                        val sid = previewSpeaker(word.startSeconds, word.endSeconds, result.turns)
                        log("[%.3f–%.3f] %s: %s".format(
                            Locale.ROOT, word.startSeconds, word.endSeconds, sid, word.text,
                        ))
                    }
                    if (asr.words.size > 100) log("... showing first 100 words")
                    log("M3 combined preview finished. Final JSON v1 integration is M4.")
                }
            } catch (t: Throwable) {
                log("ERROR: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                diarButton.isEnabled = true
                fullButton.isEnabled = true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        container.addView(TextView(this).apply {
            text = "404BNF M3 — локальная диаризация\nТестовый WAV: PCM16 mono 16 кГц, до 120 сек. " +
                "Только локальные модели. В режиме M2+M3 ASR пока считается непроверенным."
            textSize = 17f
        })
        diarButton = Button(this).apply { text = "Проверить только M3 (Silero + WeSpeaker)" }
        fullButton = Button(this).apply { text = "Проверить M2 + M3 вместе" }
        diarButton.setOnClickListener { full = false; wavPicker.launch(arrayOf("audio/*", "application/octet-stream")) }
        fullButton.setOnClickListener { full = true; wavPicker.launch(arrayOf("audio/*", "application/octet-stream")) }
        container.addView(diarButton)
        container.addView(fullButton)
        output = TextView(this).apply { text = "Готов к тесту\n"; textSize = 13f }
        container.addView(ScrollView(this).apply { addView(output) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        setContentView(container)
    }

    private fun log(text: String) = runOnUiThread { output.append(text + "\n") }

    private fun previewSpeaker(start: Double, end: Double, turns: List<DiarTurn>): String {
        val support = linkedMapOf<String, Double>()
        for (turn in turns) {
            if (turn.speakerId == "UNKNOWN") continue
            val seconds = max(0.0, min(end, turn.end) - max(start, turn.start))
            if (seconds > 0) support[turn.speakerId] = (support[turn.speakerId] ?: 0.0) + seconds
        }
        val candidates = support.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
        if (candidates.size > 1 && candidates[1].value >= candidates[0].value * 0.90) return "UNKNOWN"
        if (candidates.isNotEmpty()) return candidates[0].key
        val mid = (start + end) / 2
        val near = turns.filter { it.speakerId != "UNKNOWN" }.map {
            Triple(max(0.0, max(it.start - mid, mid - it.end)), it.speakerId, it.start)
        }.filter { it.first <= 0.35 }.sortedWith(compareBy({ it.first }, { it.second }))
        if (near.size > 1 && near[0].second != near[1].second && near[1].first - near[0].first < 0.04) return "UNKNOWN"
        return near.firstOrNull()?.second ?: "UNKNOWN"
    }
}
