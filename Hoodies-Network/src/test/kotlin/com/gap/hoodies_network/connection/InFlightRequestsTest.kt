package com.gap.hoodies_network.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InFlightRequestsTest {

    @Test
    fun cancelDisconnectsAttachedConnection() {
        val inFlight = InFlightRequests()
        val request = TestRequest("a")
        val connection = FakeConnection()

        assertTrue(inFlight.attach(request, connection))
        inFlight.cancel(request)

        assertTrue(connection.disconnected)
        assertTrue(inFlight.isCancelled(request))
    }

    @Test
    fun attachAfterCancelIsRejected() {
        val inFlight = InFlightRequests()
        val request = TestRequest("a")
        inFlight.cancel(request)

        val connection = FakeConnection()
        assertFalse(inFlight.attach(request, connection))
        assertFalse(connection.disconnected)
    }

    @Test
    fun cancelAfterDetachDoesNotTouchConnection() {
        val inFlight = InFlightRequests()
        val request = TestRequest("a")
        val connection = FakeConnection()
        inFlight.attach(request, connection)
        inFlight.detach(request, connection)

        inFlight.cancel(request)

        assertFalse(connection.disconnected)
    }

    @Test
    fun cancellationIsPerRequest() {
        val inFlight = InFlightRequests()
        val cancelled = TestRequest("a")
        val other = TestRequest("b")
        val otherConnection = FakeConnection()
        inFlight.attach(other, otherConnection)

        inFlight.cancel(cancelled)

        assertFalse(inFlight.isCancelled(other))
        assertFalse(otherConnection.disconnected)
    }
}
