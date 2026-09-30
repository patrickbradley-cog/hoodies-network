package com.sun.net.httpserver

import java.io.IOException

/**
 * Source- and binary-compatible replacement for the `com.sun.net.httpserver.HttpHandler` interface
 * that used to be provided by the bundled `http-2.2.1.jar`.
 */
fun interface HttpHandler {
    /**
     * Handles a single request/response exchange
     */
    @Throws(IOException::class)
    fun handle(exchange: HttpExchange)
}
