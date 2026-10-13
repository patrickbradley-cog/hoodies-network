package com.gap.hoodies_network.persistence

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gap.hoodies_network.cache.EncryptedCache
import com.gap.hoodies_network.cache.configuration.CacheEnabled
import com.gap.hoodies_network.cookies.PersistentCookieJar
import com.gap.hoodies_network.core.Response
import com.gap.hoodies_network.request.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpCookie
import java.net.URI
import java.time.Duration

/**
 * In-place upgrade (risk R1): the Room 2.4.2 build of this test APK writes Keystore-encrypted cache
 * rows and persistent cookies through the public API (assets/room-2.4.2/Room242FixtureGenerator.kt.txt,
 * `seedInPlaceUpgrade`), then this APK is installed over it with `install -r`. These tests read the data
 * back through the public API with the upgraded Room.
 *
 * Needs the seed on the device; pass `-Pandroid.testInstrumentationRunnerArguments.requireRoomUpgradeSeed=true`
 * to fail instead of skip when it is missing. See assets/room-2.4.2/README.md.
 */
@RunWith(AndroidJUnit4::class)
class RoomInPlaceUpgradeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun encryptedCacheRowWrittenByRoom242IsReadableAfterUpgrade() = assertCachedPayload("encrypted", encryption = true)

    @Test
    fun plainCacheRowWrittenByRoom242IsReadableAfterUpgrade() = assertCachedPayload("plain", encryption = false)

    @Test
    fun persistentCookiesWrittenByRoom242AreReadableAfterUpgrade() {
        val seed = loadSeed()
        val jar = PersistentCookieJar(seed.getString("cookieInstance"), context)
        val seeded = seed.getJSONObject("cookies")
        val readBackByRoom242 = seed.getJSONObject("cookiesReadBackByRoom242")

        assertEquals(seeded.length(), jar.getAllCookies().size)
        readBackByRoom242.keys().forEach { host ->
            assertEquals(
                "cookies for $host differ from what the Room 2.4.2 build read back",
                readBackByRoom242.getJSONArray(host).toNameValuePairs(),
                jar.getCookiesForHost(URI(host)).map { it.name to it.value },
            )
        }
        seeded.keys().forEach { name ->
            val entry = seeded.getJSONObject(name)
            val readableBefore = readBackByRoom242.getJSONArray(entry.getString("host")).toNameValuePairs()
            if (readableBefore.any { it.first == name }) {
                val cookie = jar.getCookiesForHost(URI(entry.getString("host"))).singleOrNull { it.name == name }
                assertNotNull("cookie $name missing after upgrade", cookie)
                assertEquals(entry.getString("value"), cookie!!.value)
                assertEquals("/", cookie.path)
            }
        }
    }

    @Test
    fun cookieJarUpgradedFromRoom242AcceptsAndReturnsNewCookies() {
        val seed = loadSeed()
        val jar = PersistentCookieJar(seed.getString("cookieInstance"), context)
        val before = jar.getAllCookies().size
        val host = URI("http://localhost")
        val cookie = HttpCookie("afterUpgrade", "room-2.8.5").apply {
            path = "/"
            maxAge = 3600
        }

        jar.addCookieForHost(host, cookie)
        try {
            assertEquals(before + 1, jar.getAllCookies().size)
            val stored = jar.getCookiesForHost(host).singleOrNull { it.name == "afterUpgrade" }
            assertNotNull("cookie written after upgrade not returned", stored)
            assertEquals("room-2.8.5", stored!!.value)
            assertEquals("/", stored.path)
            assertEquals(3600L, stored.maxAge)
        } finally {
            jar.cookieStore.remove(host, cookie)
        }
        assertEquals(before, jar.getAllCookies().size)
    }

    private fun JSONArray.toNameValuePairs(): List<Pair<String?, String?>> = (0 until length()).map {
        val o = getJSONObject(it)
        (if (o.isNull("name")) null else o.getString("name")) to (if (o.isNull("value")) null else o.getString("value"))
    }

    private fun assertCachedPayload(kind: String, encryption: Boolean) {
        val entry = loadSeed().getJSONObject("cache").getJSONObject(kind)
        val cache = EncryptedCache(CacheEnabled(Duration.ofDays(36500), encryption, context))
        val request = CapturingRequest(entry.getString("url"), entry.getString("body"), cache)

        assertFalse(cache.isDataStale(request))
        cache.getCachedData(request)
        assertEquals(entry.getString("payload"), request.delivered?.getData()?.decodeToString())
    }

    private fun loadSeed(): JSONObject {
        val seedFile = File(context.filesDir, SEED_FILE)
        if (!seedFile.exists()) {
            val message = "no Room 2.4.2 seed at $seedFile; see assets/room-2.4.2/README.md"
            if (InstrumentationRegistry.getArguments().getString("requireRoomUpgradeSeed") == "true") fail(message)
            assumeTrue(message, false)
        }
        return JSONObject(seedFile.readText())
    }

    private class CapturingRequest(url: String, body: String, cache: EncryptedCache) :
        Request<Any>(url, Method.POST, body, null, cache, null) {
        var delivered: Response<Any>? = null
        override fun parseNetworkResponse(response: Response<Any>?): Response<Any>? = response
        override fun deliverResponse(response: Response<Any>?) {
            delivered = response
        }
    }

    companion object {
        const val SEED_FILE = "room242-upgrade-seed.json"
    }
}
