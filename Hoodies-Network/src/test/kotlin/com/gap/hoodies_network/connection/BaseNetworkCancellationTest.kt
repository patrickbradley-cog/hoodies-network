package com.gap.hoodies_network.connection

import com.gap.hoodies_network.core.HoodiesNetworkError
import com.gap.hoodies_network.core.Response
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class BaseNetworkCancellationTest {

    @Test
    fun cancellingRequestAbortsBlockedHttpUrlConnection() = runTest {
        ServerSocket(0).use { server ->
            val requestReceived = CountDownLatch(1)
            val clients = CopyOnWriteArrayList<Socket>()
            thread(isDaemon = true) {
                runCatching {
                    while (true) {
                        val socket = server.accept()
                        clients += socket
                        socket.getInputStream().bufferedReader().readLine()
                        requestReceived.countDown()
                    }
                }
            }

            val inFlight = InFlightRequests()
            val network = BaseNetwork(null, null, inFlight)
            val request = TestRequest("slow", url = "http://127.0.0.1:${server.localPort}/slow")

            var outcome: Result<Response<Any>>? = null
            val caller = thread(isDaemon = true) { outcome = runCatching { network.executeRequest(request) } }
            assertTrue("server never received the request", requestReceived.await(10, TimeUnit.SECONDS))

            val started = System.nanoTime()
            inFlight.cancel(request)
            caller.join(10_000)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

            assertFalse("request still blocked ${elapsedMs}ms after cancel", caller.isAlive)
            assertTrue(outcome?.exceptionOrNull() is HoodiesNetworkError)
            clients.forEach { it.close() }
        }
    }

    @Test
    fun requestCancelledBeforeConnectingNeverReachesServer() = runTest {
        ServerSocket(0).use { server ->
            server.soTimeout = 500
            val inFlight = InFlightRequests()
            val network = BaseNetwork(null, null, inFlight)
            val request = TestRequest("never", url = "http://127.0.0.1:${server.localPort}/never")
            inFlight.cancel(request)

            val outcome = withContext(Dispatchers.IO) { runCatching { network.executeRequest(request) } }

            assertTrue(outcome.exceptionOrNull() is HoodiesNetworkError)
            val connected = runCatching { server.accept().close() }.isSuccess
            assertTrue("server should not have been contacted", !connected)
        }
    }
}
