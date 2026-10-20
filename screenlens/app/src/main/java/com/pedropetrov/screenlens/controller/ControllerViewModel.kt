package com.pedropetrov.screenlens.controller

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pedropetrov.screenlens.Prefs
import com.pedropetrov.screenlens.ai.ClaudeTutor
import com.pedropetrov.screenlens.ai.Mode
import com.pedropetrov.screenlens.net.FoundCamera
import com.pedropetrov.screenlens.net.NsdBrowser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Turn(val question: String, val answer: String)

data class ControllerState(
    val discovered: List<FoundCamera> = emptyList(),
    val connectedTo: String? = null,
    val deviceName: String = "",
    val minZoom: Float = 1f,
    val maxZoom: Float = 1f,
    val zoom: Float = 1f,
    val torch: Boolean = false,
    val livePreview: Bitmap? = null,
    val capturing: Boolean = false,
    val captured: Bitmap? = null,
    val askMode: Boolean = false,   // show the "what should I present?" prompt
    val turns: List<Turn> = emptyList(),
    val thinking: Boolean = false,
    val error: String? = null,
)

class ControllerViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)

    private val _state = MutableStateFlow(ControllerState())
    val state: StateFlow<ControllerState> = _state

    private var client: CameraClient? = null
    private var capturedJpeg: ByteArray? = null
    private var tutor: ClaudeTutor? = null
    private var previewJob: Job? = null
    private var aiJob: Job? = null

    private val browser = NsdBrowser(app) { list -> _state.update { it.copy(discovered = list) } }.also { it.start() }

    fun connect(host: String, port: Int, pin: String) {
        prefs.lastHost = "$host:$port"
        prefs.lastPin = pin
        val c = CameraClient(host, port, pin)
        viewModelScope.launch {
            try {
                val status = withContext(Dispatchers.IO) { c.status() }
                client = c
                _state.update {
                    it.copy(
                        connectedTo = "$host:$port",
                        deviceName = status.optString("device"),
                        minZoom = status.optDouble("minZoom", 1.0).toFloat(),
                        maxZoom = status.optDouble("maxZoom", 1.0).toFloat(),
                        error = null,
                    )
                }
                startPreview()
            } catch (e: Exception) {
                _state.update { it.copy(error = "Could not connect: ${e.message}") }
            }
        }
    }

    fun disconnect() {
        previewJob?.cancel()
        aiJob?.cancel()
        client = null
        _state.update { ControllerState(discovered = it.discovered) }
    }

    private fun startPreview() {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            while (isActive) {
                val c = client ?: break
                val frame = withContext(Dispatchers.IO) {
                    runCatching { c.preview() }.getOrNull()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                }
                if (frame != null) _state.update { it.copy(livePreview = frame) }
                delay(200)
            }
        }
    }

    fun setTorch(on: Boolean) = cameraCommand { it.torch(on); _state.update { s -> s.copy(torch = on) } }
    fun focus() = cameraCommand { it.focus() }
    fun previewZoom(ratio: Float) = _state.update { it.copy(zoom = ratio) }
    fun applyZoom() {
        val ratio = _state.value.zoom
        cameraCommand { it.zoom(ratio) }
    }

    private fun cameraCommand(block: (CameraClient) -> Unit) {
        val c = client ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { block(c) }.onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun capture() {
        val c = client ?: return
        _state.update { it.copy(capturing = true, error = null) }
        viewModelScope.launch {
            try {
                val jpeg = withContext(Dispatchers.IO) { c.capture() }
                capturedJpeg = jpeg
                previewJob?.cancel()
                _state.update {
                    it.copy(
                        capturing = false,
                        captured = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size),
                        askMode = true,
                        turns = emptyList(),
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(capturing = false, error = "Capture failed: ${e.message}") }
            }
        }
    }

    /** Goes back to the live view for a new photo. */
    fun retake() {
        aiJob?.cancel()
        capturedJpeg = null
        tutor = null
        _state.update { it.copy(captured = null, askMode = false, turns = emptyList(), thinking = false, error = null) }
        startPreview()
    }

    fun ask(mode: Mode, extra: String) {
        val jpeg = capturedJpeg ?: return
        val key = prefs.apiKey
        if (key.isBlank()) {
            _state.update { it.copy(error = "Add your Claude API key in Settings first.") }
            return
        }
        val t = ClaudeTutor(key).also { tutor = it }
        val label = if (extra.isBlank()) mode.label else "${mode.label}: $extra"
        runTurn(label) { onText -> t.start(jpeg, mode, extra, onText) }
    }

    fun followUp(question: String) {
        val t = tutor ?: return
        if (question.isBlank()) return
        runTurn(question) { onText -> t.followUp(question, onText) }
    }

    private fun runTurn(question: String, call: ((String) -> Unit) -> ClaudeTutor.Outcome) {
        aiJob?.cancel()
        _state.update { it.copy(askMode = false, thinking = true, error = null, turns = it.turns + Turn(question, "")) }
        aiJob = viewModelScope.launch(Dispatchers.IO) {
            val job = coroutineContext[Job]
            try {
                val outcome = call { delta ->
                    // Stops the blocking stream when the user retakes or disconnects.
                    if (job?.isActive == false) throw CancellationException("Cancelled")
                    _state.update { s ->
                        val last = s.turns.last()
                        s.copy(thinking = false, turns = s.turns.dropLast(1) + last.copy(answer = last.answer + delta))
                    }
                }
                _state.update { s ->
                    if (outcome == ClaudeTutor.Outcome.REFUSED) {
                        s.copy(thinking = false, error = "Claude declined to answer this one. Try another photo or question.")
                    } else {
                        s.copy(thinking = false)
                    }
                }
            } catch (e: CancellationException) {
                // Superseded by a newer action; nothing to report.
            } catch (e: Exception) {
                _state.update { s ->
                    s.copy(
                        thinking = false,
                        turns = s.turns.dropLast(1),
                        askMode = s.turns.size <= 1,
                        error = "Claude request failed: ${e.message}",
                    )
                }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    override fun onCleared() {
        browser.stop()
    }
}
