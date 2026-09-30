package com.gap.hoodies_network.mockwebserver

import android.content.Context
import java.util.concurrent.TimeUnit

class ServerManager {
    companion object {
        private val START_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(60)

        var server: MockWebServerManager? = null

        fun setup(context:Context?) {

            val builder = MockWebServerManager.Builder()

            //HttpBin replica
            builder.addContext("/post", Post())
            builder.addContext("/get", Get())
            builder.addContext("/options", Options())
            builder.addContext("/put", Put())
            builder.addContext("/delete", Delete())
            builder.addContext("/patch", Patch())
            builder.addContext("/html", Html())
            builder.addContext("/image", ImageReturn(context!!))

            //Postman echo replica
            builder.addContext("/echo", EchoDelay())

            //OpenWeatherMap sample replica
            builder.addContext("/weather", Weather())

            //JsonTodos replica
            builder.addContext("/todos", JsonTodos())

            //Cookie testing setup
            builder.addContext("/cookie_factory", CookieFactory())
            builder.addContext("/cookie_inspector", CookieInspector())

            //Interceptor testing setup
            builder.addContext("/wants_key", WantsKeyHeader())
            //Sometimes the tests get run in parallel and fail because the port is already in use
            //For those cases, we will wait here until the server can start

            val deadline = System.nanoTime() + START_TIMEOUT_NANOS
            while (true) {
                try {
                    server = builder.start()
                    return
                } catch (e: Exception) {
                    if (System.nanoTime() > deadline) {
                        throw IllegalStateException("Mock web server did not start within 60s", e)
                    }
                    Thread.sleep(100)
                }
            }
        }

        fun stop() {
            server?.stop()
        }
    }
}