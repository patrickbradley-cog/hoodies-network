package com.gap.hoodies_network.core

import com.gap.hoodies_network.connection.FakeNetwork
import com.gap.hoodies_network.connection.InFlightRequests
import com.gap.hoodies_network.connection.queue.RequestQueue
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class HoodiesNetworkClientConcurrencyTest {

    private val ioScheduler = TestCoroutineScheduler()

    private fun TestScope.client(network: FakeNetwork): Pair<HoodiesNetworkClient, RequestQueue> {
        val queue = RequestQueue(
            network,
            Executor { it.run() },
            StandardTestDispatcher(testScheduler),
            InFlightRequests()
        )
        val client = HoodiesNetworkClient.Builder()
            .baseUrl("http://localhost/")
            .dispatchers(HoodiesDispatchers(io = StandardTestDispatcher(ioScheduler)))
            .requestQueue(queue)
            .build()
        return client to queue
    }

    @Test
    fun requestPreparationRunsOnInjectedDispatcher() = runTest {
        val network = FakeNetwork { Response("hello".toByteArray()) }
        val (client, _) = client(network)

        val call = async { client.get<String>("greeting") }
        testScheduler.advanceUntilIdle()
        assertTrue(network.executed.isEmpty())

        ioScheduler.advanceUntilIdle()
        testScheduler.advanceUntilIdle()

        assertEquals(1, network.executed.size)
        assertEquals("http://localhost/greeting", network.executed.single().getUrl())
        assertEquals("hello", (call.await() as Success).value)
    }

    @Test
    fun cancellingCallerBeforeEnqueueSkipsNetwork() = runTest {
        val network = FakeNetwork()
        val (client, queue) = client(network)

        val call = launch { client.get<String>("greeting") }
        testScheduler.advanceUntilIdle()
        call.cancel()
        ioScheduler.advanceUntilIdle()
        testScheduler.advanceUntilIdle()

        assertTrue(call.isCancelled)
        assertTrue(network.executed.isEmpty())
        assertEquals(0, queue.size())
    }

    @Test
    fun cancellingCallerWhileQueuedRemovesRequest() = runTest {
        val network = FakeNetwork()
        val queueScheduler = TestCoroutineScheduler()
        val queue = RequestQueue(
            network,
            Executor { it.run() },
            StandardTestDispatcher(queueScheduler),
            InFlightRequests()
        )
        val client = HoodiesNetworkClient.Builder()
            .baseUrl("http://localhost/")
            .dispatchers(HoodiesDispatchers(io = StandardTestDispatcher(testScheduler)))
            .requestQueue(queue)
            .build()

        val call = launch { client.get<String>("greeting") }
        testScheduler.advanceUntilIdle()
        assertEquals(1, queue.size())

        call.cancel()
        testScheduler.advanceUntilIdle()
        queueScheduler.advanceUntilIdle()

        assertTrue(call.isCancelled)
        assertTrue(network.executed.isEmpty())
        assertEquals(0, queue.size())
    }
}
