package com.pedropetrov.screenlens.camera

import android.os.Build
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * HTTP API the camera phone exposes on the local Wi-Fi. Every request must carry
 * the camera's PIN in the `X-Pin` header.
 *
 *   GET /status               device info and zoom range
 *   GET /preview              latest low-res JPEG frame
 *   GET /capture              full-quality JPEG photo
 *   GET /torch?on=1|0         torch on/off
 *   GET /zoom?ratio=2.5       zoom
 *   GET /focus                re-focus on the centre
 */
class CameraHttpServer(
    port: Int,
    private val pin: String,
    private val engine: CameraEngine,
    private val onEvent: (String) -> Unit,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        if (session.headers["x-pin"] != pin) return text(Response.Status.UNAUTHORIZED, "Wrong PIN")
        val params = session.parameters
        return try {
            when (session.uri) {
                "/status" -> {
                    val (min, max) = engine.zoomRange()
                    val json = JSONObject()
                        .put("device", Build.MODEL)
                        .put("minZoom", min.toDouble())
                        .put("maxZoom", max.toDouble())
                    newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
                }
                "/preview" -> engine.latestPreview()?.let(::jpeg)
                    ?: text(Response.Status.SERVICE_UNAVAILABLE, "No frame yet")
                "/capture" -> {
                    onEvent("Photo requested by controller")
                    val photo = runBlocking { withTimeout(CAPTURE_TIMEOUT_MS) { engine.capture() } }
                    onEvent("Photo sent (${photo.size / 1024} KB)")
                    jpeg(photo)
                }
                "/torch" -> {
                    engine.setTorch(params["on"]?.firstOrNull() == "1")
                    text(Response.Status.OK, "ok")
                }
                "/zoom" -> {
                    engine.setZoom(params["ratio"]?.firstOrNull()?.toFloatOrNull() ?: 1f)
                    text(Response.Status.OK, "ok")
                }
                "/focus" -> {
                    engine.focusCenter()
                    text(Response.Status.OK, "ok")
                }
                else -> text(Response.Status.NOT_FOUND, "Not found")
            }
        } catch (e: Exception) {
            onEvent("Error: ${e.message}")
            text(Response.Status.INTERNAL_ERROR, e.message ?: "Camera error")
        }
    }

    private fun jpeg(bytes: ByteArray): Response =
        newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong())

    private fun text(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, MIME_PLAINTEXT, body)

    companion object {
        private const val CAPTURE_TIMEOUT_MS = 15_000L
    }
}
