package com.gap.hoodies_network

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.gap.hoodies_network.cookies.CookieJar
import com.gap.hoodies_network.cookies.PersistentCookieJar
import com.gap.hoodies_network.core.Failure
import com.gap.hoodies_network.core.HoodiesNetworkClient
import com.gap.hoodies_network.core.Success
import com.gap.hoodies_network.mockwebserver.HttpCall
import com.gap.hoodies_network.mockwebserver.MockWebServerManager
import com.gap.hoodies_network.mockwebserver.WebServerHandler
import com.sun.net.httpserver.Headers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpCookie
import java.net.URI

/**
 * Regression tests for sfind-2749f44ff1ca46c293084c5f41654007: the persistent cookie store must honour the
 * CookieStore contract (replace by name/domain/path, drop Max-Age=0 and expired cookies, identity-scoped removal).
 */
class PersistentCookieStoreTest {

    private val port = 6971
    private val baseUrl = "http://localhost:$port"
    private val jarName = "persistent_cookie_store_test"
    private lateinit var server: MockWebServerManager
    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class SetCookie(private val value: String) : WebServerHandler() {
        override fun handleRequest(call: HttpCall) {
            post {
                call.getBodyString()
                val headers = Headers()
                headers.add("Set-Cookie", value)
                call.setResponseHeaders(headers)
                call.respond(200, "{}")
            }
        }
    }

    private class EchoCookieHeader : WebServerHandler() {
        override fun handleRequest(call: HttpCall) {
            post {
                call.getBodyString()
                val sent = call.getHeaders()["Cookie"]?.joinToString("; ") ?: ""
                call.respond(200, JSONObject().put("cookie", sent).toString())
            }
        }
    }

    @Before
    fun start() {
        val builder = MockWebServerManager.Builder().usePort(port)
            .addContext("/login", SetCookie("session=ALICE-SESSION-TOKEN; Path=/"))
            .addContext("/logout", SetCookie("session=; Max-Age=0; Path=/"))
            .addContext("/rotate", SetCookie("session=ROTATED-SESSION-TOKEN; Path=/"))
            .addContext("/sticky", SetCookie("lb=node-1; Path=/"))
            .addContext("/short_lived", SetCookie("temp=SHORT-LIVED; Max-Age=2; Path=/"))
            .addContext("/whoami", EchoCookieHeader())
        var started = false
        while (!started) {
            try {
                server = builder.start()
                started = true
            } catch (e: Exception) {
                Thread.sleep(100)
            }
        }
        PersistentCookieJar(jarName, ctx).removeAllCookies()
    }

    @After
    fun stop() {
        PersistentCookieJar(jarName, ctx).removeAllCookies()
        server.stop()
    }

    private fun client(jar: CookieJar) = HoodiesNetworkClient.Builder()
        .baseUrl(baseUrl)
        .enableCookiesWithCookieJar(jar)
        .build()

    private suspend fun call(client: HoodiesNetworkClient, path: String): String =
        when (val result = client.post<String>(path, JSONObject())) {
            is Success -> result.value ?: ""
            is Failure -> throw result.reason
        }

    private suspend fun cookieHeaderSent(client: HoodiesNetworkClient): String =
        JSONObject(call(client, "/whoami")).getString("cookie")

    private fun rowCount(): Long =
        SQLiteDatabase.openDatabase(ctx.getDatabasePath(jarName).path, null, SQLiteDatabase.OPEN_READONLY).use {
            it.compileStatement("SELECT COUNT(*) FROM encryptedcookie").simpleQueryForLong()
        }

    @Test
    fun logoutWithMaxAgeZeroDeletesSessionAcrossRestarts() = runBlocking {
        val client = client(PersistentCookieJar(jarName, ctx))
        call(client, "/login")
        assertTrue(cookieHeaderSent(client).contains("session=ALICE-SESSION-TOKEN"))

        call(client, "/logout")
        val afterLogout = cookieHeaderSent(client)
        assertFalse("session still sent after logout: $afterLogout", afterLogout.contains("ALICE-SESSION-TOKEN"))
        assertFalse("deletion cookie was stored: $afterLogout", afterLogout.contains("session="))
        assertEquals(0L, rowCount())

        val afterRestart = cookieHeaderSent(client(PersistentCookieJar(jarName, ctx)))
        assertEquals("", afterRestart)
    }

    @Test
    fun rotatedSessionReplacesPreviousValue() = runBlocking {
        val jar = PersistentCookieJar(jarName, ctx)
        val client = client(jar)
        call(client, "/login")
        call(client, "/rotate")

        val sent = cookieHeaderSent(client)
        assertFalse("old session still sent: $sent", sent.contains("ALICE-SESSION-TOKEN"))
        assertTrue(sent.contains("session=ROTATED-SESSION-TOKEN"))
        assertEquals(listOf("session=ROTATED-SESSION-TOKEN"), jar.getCookiesForHost(URI(baseUrl)).map { "${it.name}=${it.value}" })
        assertEquals(1L, rowCount())
    }

    @Test
    fun reissuedCookieDoesNotAccumulate() = runBlocking {
        val client = client(PersistentCookieJar(jarName, ctx))
        repeat(50) { call(client, "/sticky") }

        val sent = cookieHeaderSent(client)
        assertEquals(1, Regex("lb=node-1").findAll(sent).count())
        assertEquals(1L, rowCount())
    }

    @Test
    fun expiredCookieIsNotSentAndIsPurged() = runBlocking {
        val client = client(PersistentCookieJar(jarName, ctx))
        call(client, "/short_lived")
        assertTrue(cookieHeaderSent(client).contains("SHORT-LIVED"))

        Thread.sleep(3500)

        val restartedJar = PersistentCookieJar(jarName, ctx)
        assertFalse(cookieHeaderSent(client(restartedJar)).contains("SHORT-LIVED"))
        assertTrue(restartedJar.getAllCookies().isEmpty())
        assertEquals(0L, rowCount())
    }

    @Test
    fun alreadyExpiredCookieIsNotStored() {
        val jar = PersistentCookieJar(jarName, ctx)
        jar.addCookieForHost(URI(baseUrl), HttpCookie("gone", "value").apply { maxAge = 0 })
        assertTrue(jar.getAllCookies().isEmpty())
        assertEquals(0L, rowCount())
    }

    @Test
    fun removeOnlyDeletesMatchingCookieIdentity() {
        val jar = PersistentCookieJar(jarName, ctx)
        val hostA = URI("https://a.example")
        val hostB = URI("https://b.example")
        // name/domain swapped: identical HttpCookie.hashCode(), different identity
        val first = HttpCookie("x", "first").apply { domain = "y"; path = "/" }
        val second = HttpCookie("y", "second").apply { domain = "x"; path = "/" }
        assertEquals(first.hashCode(), second.hashCode())
        jar.addCookieForHost(hostA, first)
        jar.addCookieForHost(hostB, second)

        assertTrue(jar.cookieStore.remove(hostA, first))

        assertTrue(jar.getCookiesForHost(hostA).isEmpty())
        assertEquals(listOf("y=second"), jar.getCookiesForHost(hostB).map { "${it.name}=${it.value}" })
    }

    @Test
    fun rowMovedToAnotherHostIsRejected() {
        val jar = PersistentCookieJar(jarName, ctx)
        jar.addCookieForHost(URI("https://attacker.example"), HttpCookie("session", "PLANTED").apply { path = "/" })

        SQLiteDatabase.openDatabase(ctx.getDatabasePath(jarName).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE encryptedcookie SET host = 'http://localhost'")
        }

        assertTrue(PersistentCookieJar(jarName, ctx).getCookiesForHost(URI(baseUrl)).isEmpty())
    }
}
