package com.pedropetrov.screenlens.controller

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Talks to the camera phone's [com.pedropetrov.screenlens.camera.CameraHttpServer]. Blocking calls. */
class CameraClient(private val host: String, private val port: Int, private val pin: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun status(): JSONObject = JSONObject(String(get("/status")))
    fun preview(): ByteArray = get("/preview")
    fun capture(): ByteArray = get("/capture")
    fun torch(on: Boolean) { get("/torch", "on" to if (on) "1" else "0") }
    fun zoom(ratio: Float) { get("/zoom", "ratio" to ratio.toString()) }
    fun focus() { get("/focus") }

    private fun get(path: String, vararg query: Pair<String, String>): ByteArray {
        val url = HttpUrl.Builder().scheme("http").host(host).port(port).encodedPath(path).apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val request = Request.Builder().url(url).header("X-Pin", pin).build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) throw IOException("Camera phone: ${response.code} ${String(body)}")
            return body
        }
    }
}
