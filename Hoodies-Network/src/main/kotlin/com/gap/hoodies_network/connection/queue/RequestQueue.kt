package com.gap.hoodies_network.connection.queue

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import com.gap.hoodies_network.connection.BaseNetwork
import com.gap.hoodies_network.connection.InFlightRequests
import com.gap.hoodies_network.connection.Network
import com.gap.hoodies_network.core.HoodiesNetworkError
import com.gap.hoodies_network.core.Response
import com.gap.hoodies_network.delivery.ResponseDelivery
import com.gap.hoodies_network.delivery.ResponseDeliveryExecutor
import com.gap.hoodies_network.request.Request
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocketFactory

/**
 * RequestQueue class handles enqueue and dequeue of requests' queue
 *
 * Requests are executed by [DEFAULT_NETWORK_THREAD_POOL_SIZE] worker coroutines running on
 * [networkDispatcher], children of a queue-owned [SupervisorJob]. Responses are delivered on
 * [deliveryExecutor] (the main thread by default).
 *
 * @param sslHost
 * @param sslSocketFactory
 *
 */
class RequestQueue internal constructor(
    network: Network,
    deliveryExecutor: Executor,
    networkDispatcher: CoroutineDispatcher,
    private val inFlightRequests: InFlightRequests
) {

    constructor(sslHost: String?, sslSocketFactory: SSLSocketFactory?) :
        this(sslHost, sslSocketFactory, InFlightRequests())

    private constructor(
        sslHost: String?,
        sslSocketFactory: SSLSocketFactory?,
        inFlightRequests: InFlightRequests
    ) : this(
        BaseNetwork(sslHost, sslSocketFactory, inFlightRequests),
        Handler(Looper.getMainLooper()).let { handler -> Executor { handler.post(it) } },
        newNetworkDispatcher(),
        inFlightRequests
    )

    private val mNetworkQueue: PriorityBlockingQueue<Request<Any>> =
        PriorityBlockingQueue<Request<Any>>()

    /** One element per enqueued request; workers suspend on it instead of blocking on [mNetworkQueue]. */
    private val pending = Channel<Unit>(Channel.UNLIMITED)

    private val mNetwork: Network = network
    private val mResponseDelivery: ResponseDelivery =
        CancellationAwareDelivery(deliveryExecutor, inFlightRequests)
    private val scope = CoroutineScope(
        SupervisorJob() + networkDispatcher + CoroutineName("HoodiesRequestQueue")
    )

    fun enqueue(request: Request<Any>) {
        try {
            mNetworkQueue.add(request)
            pending.trySend(Unit)
        } catch (e: Exception) {
            Log.e("exception in enqueue", e.toString())
        }
    }

    fun dequeue(): Request<Any>? {
        return mNetworkQueue.poll()
    }

    fun hasItems(): Boolean {
        return !mNetworkQueue.isEmpty()
    }

    fun size(): Int {
        return mNetworkQueue.size
    }

    /**
     * Cancels [request]: removes it from the queue if it is still waiting, disconnects its
     * [java.net.HttpURLConnection] if it is in flight, and suppresses delivery of its result.
     */
    internal fun cancel(request: Request<Any>) {
        inFlightRequests.cancel(request)
        mNetworkQueue.remove(request)
    }

    /**
     * Starts the dispatchers in this queue
     */
    private fun startDispatchers() {
        /** If any currently dispatchers are running, stop them */
        stopDispatchers()

        /**create n/w dispatchers up to the pool size */
        repeat(DEFAULT_NETWORK_THREAD_POOL_SIZE) {
            val networkHandler = NetworkHandler(mResponseDelivery)
            scope.launch {
                while (isActive) {
                    pending.receive()
                    val request = mNetworkQueue.poll() ?: continue
                    if (!inFlightRequests.isCancelled(request)) {
                        networkHandler.executeRequest(request, mNetwork)
                    }
                }
            }
        }
    }

    private fun stopDispatchers() {
        scope.coroutineContext.cancelChildren()
    }

    /** Checks cancellation on the delivery thread, immediately before the callbacks run. */
    private class CancellationAwareDelivery(
        private val deliveryExecutor: Executor,
        private val inFlightRequests: InFlightRequests
    ) : ResponseDelivery {
        private val delegate = ResponseDeliveryExecutor(Executor { it.run() })

        override fun postResponse(request: Request<Any>, response: Response<Any>) {
            deliveryExecutor.execute {
                if (!inFlightRequests.isCancelled(request)) delegate.postResponse(request, response)
            }
        }

        override fun postError(request: Request<Any>, error: HoodiesNetworkError) {
            deliveryExecutor.execute {
                if (!inFlightRequests.isCancelled(request)) delegate.postError(request, error)
            }
        }
    }

    companion object {
        private var requestQueue: RequestQueue? = null
        private const val DEFAULT_NETWORK_THREAD_POOL_SIZE = 4
        val instance: RequestQueue?
            get() {
                synchronized(RequestQueue::class.java) {
                    if (requestQueue == null) {
                        requestQueue = RequestQueue(null, null)
                    }
                    return requestQueue
                }
            }

        fun getInstance(sslHost: String?, sslSocketFactory: SSLSocketFactory?): RequestQueue? {
            synchronized(RequestQueue::class.java) {
                if (requestQueue == null) {
                    requestQueue = RequestQueue(sslHost, sslSocketFactory)
                }
                return requestQueue
            }
        }

        /** Background-priority daemon threads, one per worker, matching the former QueueHandler threads. */
        private fun newNetworkDispatcher(): CoroutineDispatcher {
            val threadCount = AtomicInteger()
            return Executors.newFixedThreadPool(DEFAULT_NETWORK_THREAD_POOL_SIZE) { runnable ->
                Thread({
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    runnable.run()
                }, "HoodiesNetwork-${threadCount.incrementAndGet()}").apply { isDaemon = true }
            }.asCoroutineDispatcher()
        }
    }

    init {
        startDispatchers()
    }
}
