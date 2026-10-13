package com.gap.hoodies_network.connection

import com.gap.hoodies_network.cache.EncryptedCache
import com.gap.hoodies_network.core.HoodiesNetworkError
import com.gap.hoodies_network.core.Response
import com.gap.hoodies_network.request.Request
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

internal class TestRequest(
    val name: String,
    val priority: Int = 0,
    url: String = "http://localhost/$name"
) : Request<Any>(url, Method.GET, EncryptedCache(), null) {
    val delivered = CopyOnWriteArrayList<Response<Any>?>()
    val errors = CopyOnWriteArrayList<HoodiesNetworkError>()
    val deliveryThreads = CopyOnWriteArrayList<Thread>()

    init {
        errorListener = Response.ErrorListener {
            deliveryThreads += Thread.currentThread()
            errors += it
        }
    }

    override fun parseNetworkResponse(response: Response<Any>?): Response<Any>? = response

    override fun deliverResponse(response: Response<Any>?) {
        deliveryThreads += Thread.currentThread()
        delivered += response
    }

    override fun compareTo(other: Request<Any>?): Int =
        (other as? TestRequest)?.priority?.compareTo(priority) ?: 0
}

internal class FakeNetwork(
    private val onExecute: (Request<Any>) -> Response<Any> = { Response(it.getUrl().toByteArray()) }
) : Network {
    val executed = CopyOnWriteArrayList<Request<Any>>()

    override fun executeRequest(request: Request<Any>): Response<Any> {
        executed += request
        return onExecute(request)
    }
}

internal class FakeConnection : HttpURLConnection(URL("http://localhost/")) {
    @Volatile
    var disconnected = false

    override fun disconnect() {
        disconnected = true
    }

    override fun usingProxy(): Boolean = false

    override fun connect() = Unit
}
