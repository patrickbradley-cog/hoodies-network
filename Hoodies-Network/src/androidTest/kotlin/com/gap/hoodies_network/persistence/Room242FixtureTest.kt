package com.gap.hoodies_network.persistence

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gap.hoodies_network.cache.persistentstorage.CacheDatabase
import com.gap.hoodies_network.cache.persistentstorage.CachedData
import com.gap.hoodies_network.cookies.persistentstorage.EncryptedCookie
import com.gap.hoodies_network.cookies.persistentstorage.EncryptedCookieDatabase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

/**
 * Opens database files created by Room 2.4.2 (see assets/room-2.4.2/README.md) with the current Room
 * version: identity hash and schema must be unchanged, and every existing row must stay readable.
 */
@RunWith(AndroidJUnit4::class)
class Room242FixtureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @get:Rule
    val cacheHelper = MigrationTestHelper(instrumentation, CacheDatabase::class.java)

    @get:Rule
    val cookieHelper = MigrationTestHelper(instrumentation, EncryptedCookieDatabase::class.java)

    @Test
    fun cacheFixtureHasUnchangedIdentityHashAndSchema() {
        val name = installFixture(CACHE_FIXTURE)
        assertFixtureMatchesExportedSchema(name, CacheDatabase::class.java, CACHE_IDENTITY_HASH_ROOM_242)
        cacheHelper.runMigrationsAndValidate(name, 1, true).close()
    }

    @Test
    fun cookieFixtureHasUnchangedIdentityHashAndSchema() {
        val name = installFixture(COOKIE_FIXTURE)
        assertFixtureMatchesExportedSchema(name, EncryptedCookieDatabase::class.java, COOKIE_IDENTITY_HASH_ROOM_242)
        cookieHelper.runMigrationsAndValidate(name, 1, true).close()
    }

    @Test
    fun cacheRowsWrittenByRoom242StayReadableAndWritable() {
        val name = installFixture(CACHE_FIXTURE)
        val db = Room.databaseBuilder(context, CacheDatabase::class.java, name).build()
        try {
            val dao = db.cacheDao()
            val plain = CachedData(1, "http://localhost:6969/get", "".hashCode(), 1_700_000_000L, b64("{\"fixture\":\"plain\"}"), null)
            val withIv = CachedData(2, "http://localhost:6969/post", "{\"a\":1}".hashCode(), 1_700_000_100L, "ZmFrZS1jaXBoZXJ0ZXh0", "AAECAwQFBgcICQoL")
            val unicode = CachedData(3, "http://localhost:6969/unicode/\u00fcn\u00ef\u00e7\u00f8d\u00e9?q=\u2713", Int.MIN_VALUE, Long.MAX_VALUE, "", null)

            assertEquals(plain, dao.get(plain.url, plain.bodyHash))
            assertEquals("{\"fixture\":\"plain\"}", Base64.getDecoder().decode(dao.get(plain.url, plain.bodyHash)!!.data).decodeToString())
            assertEquals(withIv, dao.get(withIv.url, withIv.bodyHash))
            assertEquals(listOf(withIv), dao.getByIv("AAECAwQFBgcICQoL"))
            assertEquals(unicode, dao.get(unicode.url, unicode.bodyHash))

            dao.insert(CachedData(0, "http://localhost:6969/new", 7, 1L, "bmV3", null))
            assertEquals(4, dao.get("http://localhost:6969/new", 7)!!.id)
            dao.delete(plain.url, plain.bodyHash)
            assertNull(dao.get(plain.url, plain.bodyHash))
            assertEquals(withIv, dao.get(withIv.url, withIv.bodyHash))
        } finally {
            db.close()
        }
    }

    @Test
    fun cookieRowsWrittenByRoom242StayReadableAndWritable() {
        val name = installFixture(COOKIE_FIXTURE)
        val db = Room.databaseBuilder(context, EncryptedCookieDatabase::class.java, name).build()
        try {
            val dao = db.encryptedCookieDao()
            val localhost = EncryptedCookie(1, "http://localhost", "Y29va2llLWNpcGhlcnRleHQ=", "AAECAwQFBgcICQoL", 12345)
            val example = EncryptedCookie(2, "http://example.com", "c2Vjb25kLWNvb2tpZQ==", "CwoJCAcGBQQDAgEA", -987654321)
            val nulls = EncryptedCookie(3, null, null, null, 0)

            assertEquals(listOf(localhost, example, nulls), dao.getAll())
            assertEquals(listOf(localhost), dao.getByHost("http://localhost"))
            assertEquals(listOf(example), dao.getByIv("CwoJCAcGBQQDAgEA"))
            assertEquals(listOf("http://localhost", "http://example.com", null), dao.getAllHosts())

            dao.insert(EncryptedCookie(0, "http://localhost", "bmV3", "AQEBAQEBAQEBAQEB", 1))
            assertEquals(4, dao.getByIv("AQEBAQEBAQEBAQEB").single().id)
            assertEquals(1, dao.deleteByHash(12345))
            assertEquals(listOf(example, nulls), dao.getAll().filter { it.id != 4 })
            dao.deleteAll()
            assertEquals(emptyList<EncryptedCookie>(), dao.getAll())
        } finally {
            db.close()
        }
    }

    private fun installFixture(asset: String): String {
        val name = "ws03-$asset"
        context.deleteDatabase(name)
        val target = context.getDatabasePath(name)
        target.parentFile!!.mkdirs()
        instrumentation.context.assets.open("room-2.4.2/$asset").use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return name
    }

    private fun assertFixtureMatchesExportedSchema(name: String, dbClass: Class<out RoomDatabase>, room242Hash: String) {
        val raw = SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE)
        val (fixtureHash, userVersion) = try {
            raw.rawQuery("SELECT identity_hash FROM room_master_table WHERE id = 42", null).use {
                it.moveToFirst()
                it.getString(0)
            } to raw.version
        } finally {
            raw.close()
        }
        val exported = instrumentation.context.assets.open("${dbClass.canonicalName}/1.json").use {
            JSONObject(it.readBytes().decodeToString()).getJSONObject("database")
        }
        assertEquals(1, userVersion)
        assertEquals(room242Hash, fixtureHash)
        assertEquals(1, exported.getInt("version"))
        assertEquals(room242Hash, exported.getString("identityHash"))
    }

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.encodeToByteArray())

    companion object {
        const val CACHE_FIXTURE = "room242-fixture-cache.db"
        const val COOKIE_FIXTURE = "room242-fixture-cookies.db"

        // room_master_table.identity_hash written by Room 2.4.2 into the committed fixtures.
        const val CACHE_IDENTITY_HASH_ROOM_242 = "7e46f50b0efdb1490662ae21a486aed0"
        const val COOKIE_IDENTITY_HASH_ROOM_242 = "98e4e3e9975a1a48f0d3f101c5208261"
    }
}
