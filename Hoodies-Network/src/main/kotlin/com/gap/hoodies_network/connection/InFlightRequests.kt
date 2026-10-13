package com.gap.hoodies_network.connection

import com.gap.hoodies_network.request.Request
import java.net.HttpURLConnection
import java.util.IdentityHashMap
import java.util.WeakHashMap

/**
 * Tracks cancelled requests and the [HttpURLConnection] currently serving each in-flight request,
 * so that cancelling a request aborts its connection instead of waiting for a socket timeout.
 *
 * Cancellation marks are held weakly; [Request] uses identity equality.
 */
internal class InFlightRequests {
    private val lock = Any()
    private val cancelled = WeakHashMap<Request<*>, Unit>()
    private val connections = IdentityHashMap<Request<*>, HttpURLConnection>()

    /** Marks [request] as cancelled and disconnects its open connection, if any. */
    fun cancel(request: Request<*>) {
        val connection = synchronized(lock) {
            cancelled[request] = Unit
            connections.remove(request)
        }
        connection?.disconnect()
    }

    fun isCancelled(request: Request<*>): Boolean = synchronized(lock) { cancelled.containsKey(request) }

    /**
     * Associates [connection] with [request] until [detach].
     * @return false if [request] was already cancelled; the caller must not use [connection].
     */
    fun attach(request: Request<*>, connection: HttpURLConnection): Boolean = synchronized(lock) {
        if (cancelled.containsKey(request)) {
            false
        } else {
            connections[request] = connection
            true
        }
    }

    fun detach(request: Request<*>, connection: HttpURLConnection) {
        synchronized(lock) {
            if (connections[request] === connection) connections.remove(request)
        }
    }
}
