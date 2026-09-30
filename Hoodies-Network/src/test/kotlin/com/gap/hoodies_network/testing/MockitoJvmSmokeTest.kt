package com.gap.hoodies_network.testing

import com.gap.hoodies_network.header.Header
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.net.HttpURLConnection

/**
 * Verifies the JVM unit-test toolchain: JUnit 4 on the JUnit Platform (vintage engine) and
 * Mockito 5's default inline mock maker, which can mock final Kotlin classes.
 */
class MockitoJvmSmokeTest {

    @Test
    fun mocksFinalKotlinClass() {
        val header = mock(Header::class.java)
        `when`(header.getName()).thenReturn("X-Mocked")

        assertEquals("X-Mocked", header.getName())
        verify(header).getName()
    }

    @Test
    fun mocksHttpUrlConnectionWithoutOpeningASocket() {
        val connection = mock(HttpURLConnection::class.java)
        `when`(connection.responseCode).thenReturn(HttpURLConnection.HTTP_NO_CONTENT)

        assertEquals(HttpURLConnection.HTTP_NO_CONTENT, connection.responseCode)
    }
}
