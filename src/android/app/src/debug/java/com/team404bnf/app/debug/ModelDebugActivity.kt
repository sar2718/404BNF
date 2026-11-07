package com.team404bnf.app.debug

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.team404bnf.ml.ModelCatalog
import com.team404bnf.ml.ModelEvent
import com.team404bnf.ml.ModelId
import com.team404bnf.ml.ModelManager
import com.team404bnf.ml.OnnxSessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Debug ONLY. A separate launcher in the existing 404BNF debug APK, so no
 * changes to Edik's UI/navigation. Does not run ASR or diarization yet.
 */
class ModelDebugActivity : ComponentActivity() {
    private lateinit var manager: ModelManager
    private lateinit var sessions: OnnxSessionManager
    private lateinit var logView: TextView
    private var job: Job? = null
    private val buttons = mutableListOf<Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = ModelManager(applicationContext)
        sessions = OnnxSessionManager(manager)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        root.addView(TextView(this).apply {
            text = "404BNF • Локальные модели (M1)\n" +
                   "FP32, без серверной обработки. Загрузка только по кнопке."
            textSize = 18f
        })
        addButton(root, "1. Проверить файлы и SHA-256") {
            val errors = manager.verifyInstalled()
            if (errors.isEmpty()) log("✅ 5 ONNX SHA-256 и 3 файла конфигурации проверены по размеру")
            else errors.forEach { log("⚠ $it") }
        }
        addButton(root, "2. Скачать недостающие модели (~925 МБ)") {
            log("Загрузка в filesDir/models. Не закрывай приложение во время скачивания.")
            manager.ensureInstalled { event ->
                when (event) {
                    is ModelEvent.Verified -> log("OK: ${event.asset.fileName}")
                    is ModelEvent.Installed -> log("Скачано и проверено: ${event.asset.fileName}")
                    is ModelEvent.Downloading -> {
                        // Update on every ~8 MiB. Non-blocking UI update from IO dispatcher.
                        val total = event.total?.let { " / ${it / (1024 * 1024)} МиБ" } ?: ""
                        log("${event.asset.fileName}: ${event.downloaded / (1024 * 1024)} МиБ$total")
                    }
                }
            }
            log("✅ Установка завершена. Дальнейший инференс не требует сети.")
        }
        addButton(root, "3. Открыть ONNX-сессию Silero") {
            val asset = ModelCatalog.onnxFilesFor(ModelId.SILERO_VAD).single()
            sessions.withSession(asset) { session ->
                log("✅ Silero session: inputs=${session.inputNames}; outputs=${session.outputNames}")
            }
        }
        addButton(root, "4. Открыть ONNX-сессии всех моделей (много RAM)") {
            for (model in ModelCatalog.allModels) {
                for (asset in ModelCatalog.onnxFilesFor(model)) {
                    log("Открываем ${asset.fileName} ...")
                    sessions.withSession(asset) { session ->
                        log("✅ ${asset.fileName}: inputs=${session.inputNames.size}; outputs=${session.outputNames.size}")
                    }
                }
            }
            log("✅ Все ONNX-графы открываются по очереди; инференс ещё не выполнялся.")
        }
        val scroll = ScrollView(this)
        logView = TextView(this).apply { text = "Готово к диагностике.\n"; textSize = 13f }
        scroll.addView(logView)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun addButton(root: LinearLayout, caption: String, action: suspend () -> Unit) {
        val button = Button(this).apply { text = caption }
        buttons.add(button)
        root.addView(button)
        button.setOnClickListener {
            if (job?.isActive == true) return@setOnClickListener
            buttons.forEach { it.isEnabled = false }
            job = lifecycleScope.launch {
                try {
                    action()
                } catch (cancel: CancellationException) {
                    log("Операция прервана")
                    throw cancel
                } catch (t: Throwable) {
                    log("❌ ${t.javaClass.simpleName}: ${t.message}")
                } finally {
                    buttons.forEach { it.isEnabled = true }
                }
            }
        }
    }

    private fun log(line: String) {
        runOnUiThread {
            logView.append(line + "\n")
        }
    }
}
