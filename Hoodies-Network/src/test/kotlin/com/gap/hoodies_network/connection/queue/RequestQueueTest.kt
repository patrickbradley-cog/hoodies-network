package com.gap.hoodies_network.connection.queue

import com.gap.hoodies_network.connection.FakeConnection
import com.gap.hoodies_network.connection.FakeNetwork
import com.gap.hoodies_network.connection.InFlightRequests
import com.gap.hoodies_network.connection.TestRequest
import com.gap.hoodies_network.core.HoodiesNetworkError
import com.gap.hoodies_network.request.Request
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor

class RequestQueueTest {

    private class RecordingExecutor : Executor {
        val tasks = ConcurrentLinkedQueue<Runnable>()
        override fun execute(command: Runnable) {
            tasks += command
        }

        fun runAll() {
            while (true) tasks.poll()?.run() ?: return
        }
    }

    private fun TestScope.queue(
        network: FakeNetwork,
        delivery: Executor = Executor { it.run() },
        inFlight: InFlightRequests = InFlightRequests(),
        dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
    ) = RequestQueue(network, delivery, dispatcher, inFlight)

    @Test
    fun workersRunOnInjectedDispatcher() = runTest {
        val network = FakeNetwork()
        val queue = queue(network)
        val request = TestRequest("a")

        queue.enqueue(request as Request<Any>)
        assertTrue(network.executed.isEmpty())

        testScheduler.advanceUntilIdle()

        assertEquals(listOf(request), network.executed)
        assertEquals(1, request.delivered.size)
        assertFalse(queue.hasItems())
    }

    @Test
    fun requestsAreExecutedInPriorityOrder() = runTest {
        val network = FakeNetwork()
        val queue = queue(network)
        val low = TestRequest("low", priority = 1)
        val high = TestRequest("high", priority = 10)
        val mid = TestRequest("mid", priority = 5)

        listOf(low, high, mid).forEach { queue.enqueue(it as Request<Any>) }
        assertEquals(3, queue.size())

        testScheduler.advanceUntilIdle()

        assertEquals(listOf("high", "mid", "low"), network.executed.map { (it as TestRequest).name })
    }

    @Test
    fun responsesAreDeliveredThroughResponseDelivery() = runTest {
        val delivery = RecordingExecutor()
        val network = FakeNetwork()
        val queue = queue(network, delivery)
        val request = TestRequest("a")

        queue.enqueue(request as Request<Any>)
        testScheduler.advanceUntilIdle()

        assertEquals(1, network.executed.size)
        assertTrue(request.delivered.isEmpty())
        delivery.runAll()
        assertEquals(1, request.delivered.size)
    }

    @Test
    fun errorsAreDeliveredThroughResponseDelivery() = runTest {
        val network = FakeNetwork { throw HoodiesNetworkError("boom", 500) }
        val queue = queue(network)
        val request = TestRequest("a")

        queue.enqueue(request as Request<Any>)
        testScheduler.advanceUntilIdle()

        assertEquals(1, request.errors.size)
        assertEquals(500, request.errors.single().code)
    }

    @Test
    fun dequeueRemovesRequestBeforeWorkersRun() = runTest {
        val network = FakeNetwork()
        val queue = queue(network)
        val request = TestRequest("a")

        queue.enqueue(request as Request<Any>)
        assertEquals(request, queue.dequeue())
        testScheduler.advanceUntilIdle()

        assertTrue(network.executed.isEmpty())
        assertEquals(0, queue.size())
    }

    @Test
    fun cancelledQueuedRequestIsNeverExecuted() = runTest {
        val network = FakeNetwork()
        val queue = queue(network)
        val cancelled = TestRequest("cancelled")
        val kept = TestRequest("kept")

        queue.enqueue(cancelled as Request<Any>)
        queue.enqueue(kept as Request<Any>)
        queue.cancel(cancelled)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(kept), network.executed)
        assertTrue(cancelled.delivered.isEmpty())
    }

    @Test
    fun cancellingInFlightRequestDisconnectsAndSuppressesDelivery() = runTest {
        val inFlight = InFlightRequests()
        val connection = FakeConnection()
        lateinit var queue: RequestQueue
        val network = FakeNetwork { request ->
            inFlight.attach(request, connection)
            queue.cancel(request)
            throw HoodiesNetworkError("aborted", 0)
        }
        queue = queue(network, inFlight = inFlight)
        val request = TestRequest("a")

        queue.enqueue(request as Request<Any>)
        testScheduler.advanceUntilIdle()

        assertTrue(connection.disconnected)
        assertTrue(request.errors.isEmpty())
        assertTrue(request.delivered.isEmpty())
    }

    @Test
    fun cancellingAfterResponseIsPostedSuppressesDelivery() = runTest {
        val delivery = RecordingExecutor()
        val network = FakeNetwork()
        val queue = queue(network, delivery)
        val request = TestRequest("a")

        queue.enqueue(request as Request<Any>)
        testScheduler.advanceUntilIdle()
        assertEquals(1, delivery.tasks.size)

        queue.cancel(request)
        delivery.runAll()

        assertTrue(request.delivered.isEmpty())
    }

    @Test
    fun reEnqueuedRequestRunsAgain() = runTest {
        val network = FakeNetwork()
        val queue = queue(network)
        val request = TestRequest("retry")

        queue.enqueue(request as Request<Any>)
        testScheduler.advanceUntilIdle()
        queue.enqueue(request)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf<Request<Any>>(request, request), network.executed)
        assertEquals(2, request.delivered.size)
    }
}
