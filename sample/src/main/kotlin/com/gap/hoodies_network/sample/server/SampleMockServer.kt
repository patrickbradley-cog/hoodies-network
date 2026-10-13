package com.gap.hoodies_network.sample.server

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import com.gap.hoodies_network.mockwebserver.HttpCall
import com.gap.hoodies_network.mockwebserver.MockWebServerManager
import com.gap.hoodies_network.mockwebserver.WebServerHandler
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-process mock backend for the sample, built only on the public
 * [MockWebServerManager] / [WebServerHandler] / [HttpCall] API of Hoodies-Network.
 *
 * Endpoints:
 * - `GET  /greeting` typed JSON
 * - `POST /echo`     echoes the JSON request body
 * - `GET  /image`    a generated PNG
 * - `GET  /clock/…`  JSON with a server-side hit counter (used to show cache hits)
 * - `GET  /secure`   401 unless the [TOKEN_HEADER] header carries [TOKEN_VALUE]
 */
class SampleMockServer(private val port: Int = PORT) {

    private val greetingHits = AtomicInteger()
    private val clockHitCounter = AtomicInteger()
    private var server: MockWebServerManager? = null

    /** Number of requests that actually reached `/clock` (cache hits never do). */
    val clockHits: Int get() = clockHitCounter.get()

    val baseUrl: String get() = "http://localhost:$port/"

    fun start() {
        if (server != null) return
        server = MockWebServerManager.Builder()
            .usePort(port)
            .addContext("/greeting", GreetingHandler())
            .addContext("/echo", EchoHandler())
            .addContext("/image", ImageHandler())
            .addContext("/clock", ClockHandler())
            .addContext("/secure", SecureHandler())
            .start()
    }

    fun stop() {
        server?.stop()
        server = null
    }

    private inner class GreetingHandler : WebServerHandler() {
        override fun handleRequest(call: HttpCall) {
            get {
                val body = JSONObject()
                    .put("message", "Hello from the Hoodies mock server")
                    .put("requestNumber", greetingHits.incrementAndGet())
                call.respond(200, body.toString())
            }
        }
    }

    private class EchoHandler : WebServerHandler() {
        override fun handleRequest(call: HttpCall) {
            post {
                val received = runCatching { JSONObject(call.getBodyString()) }.getOrNull()
                if (received == null) {
                    call.respond(400, JSONObject().put("error", "Body must be a JSON object").toString())
                } else {
                    val body = JSONObject()
                        .put("method", "POST")
                        .put("received", received)
                    call.respond(200, body.toString())
                }
            }
        }
    }

    private class ImageHandler : WebServerHandler() {
        override fun handleRequest(call: HttpCall) {
            get { call.respond(200, renderPng()) }
        }

        private fun renderPng(): ByteArray {
            val bitmap = createBitmap(IMAGE_SIZE, IMAGE_SIZE)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(0x1F, 0x3A, 0x5F))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(0xF2, 0x8C, 0x28)
            }
            canvas.drawCircle(IMAGE_SIZE / 2f, IMAGE_SIZE / 2f, IMAGE_SIZE / 3f, paint)
            paint.color = Color.WHITE
            paint.textSize = IMAGE_SIZE / 8f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("Hoodies", IMAGE_SIZE / 2f, IMAGE_SIZE / 2f + paint.textSize / 3f, paint)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            return out.toByteArray()
        }
    }

    private inner class ClockHandler : WebServerHandler() {
        override fun handleRequest(call: HttpCall) {
            get {
                val body = JSONObject()
                    .put("hit", clockHitCounter.incrementAndGet())
                    .put("generatedAt", Instant.now().toString())
                call.respond(200, body.toString())
            }
        }
    }

    private class SecureHandler : WebServerHandler() {
        override fun handleRequest(call: HttpCall) {
            get {
                val token = call.getHeaders()[TOKEN_HEADER]?.firstOrNull()
                if (token == TOKEN_VALUE) {
                    call.respond(200, JSONObject().put("status", "authorized").toString())
                } else {
                    call.respond(401, JSONObject().put("status", "missing or invalid token").toString())
                }
            }
        }
    }

    companion object {
        const val PORT = 8089
        const val IMAGE_SIZE = 256
        const val TOKEN_HEADER = "X-Hoodies-Token"
        const val TOKEN_VALUE = "sample-token"
    }
}
