package com.gap.hoodies_network.cookies.persistentstorage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.gap.hoodies_network.cache.EncryptedCache
import com.gap.hoodies_network.keystore.CacheKeyManager
import org.json.JSONException
import org.json.JSONObject
import java.net.HttpCookie
import java.net.URI
import java.util.*
import java.util.concurrent.Callable
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

private const val FORMAT = "format"
private const val FORMAT_VERSION = 2
private const val AAD_PREFIX = "hoodies-network-cookie:"

/**
 * Encrypted Room-backed storage for cookies, following the java.net.CookieStore contract:
 * a cookie replaces any stored cookie with the same (name, domain, path) for its host,
 * Max-Age=0/expired cookies are never stored, and expired cookies are purged on read.
 * Each row is encrypted with its host as GCM additional authenticated data, so rows cannot be moved between hosts.
 */
class EncryptedDaoWrapperForCookies(instanceName: String, context: Context) {
    private val database = Room.databaseBuilder(context, EncryptedCookieDatabase::class.java, instanceName).build()
    private val db = database.encryptedCookieDao()

    fun getAll() : List<HttpCookie> {
        return liveCookies { db.getAll() }
    }

    fun getByHost(host: URI) : List<HttpCookie> {
        return liveCookies { db.getByHost(uriToHost(host)) }
    }

    fun deleteAll() {
        db.deleteAll()
    }

    fun deleteCookie(cookie: HttpCookie) : Boolean {
        val identity = CookieIdentity.of(cookie)
        return database.runInTransaction(Callable {
            val matching = db.getAll().filter { readRow(it)?.let(CookieIdentity::of) == identity }
            deleteRows(matching)
            matching.isNotEmpty()
        })
    }

    fun getAllHosts() : List<URI> {
        //Purge expired cookies first so hosts without live cookies aren't listed
        getAll()
        return db.getAllHosts().map{ URI(it) }.toList()
    }

    fun insert(host: URI, cookie: HttpCookie) {
        val hostKey = uriToHost(host)
        val identity = CookieIdentity.of(cookie)

        database.runInTransaction(Runnable {
            deleteRows(db.getByHost(hostKey).filter { readRow(it)?.let(CookieIdentity::of) == identity })

            if (cookie.maxAge == 0L || cookie.hasExpired())
                return@Runnable

            val iv = uniqueIv()
            val cookieJson = toJson(cookie, System.currentTimeMillis()).toString().encodeToByteArray()
            val encryptedCookieJson = Base64.getEncoder().encodeToString(runAES(cookieJson, iv, Cipher.ENCRYPT_MODE, hostKey))

            db.insert(EncryptedCookie(0, hostKey, encryptedCookieJson, Base64.getEncoder().encodeToString(iv), cookie.hashCode()))
        })
    }

    /**
     * Returns the live cookies among [rows], keeping only the newest row per host and cookie identity.
     * Expired, superseded and unreadable rows are deleted.
     */
    private fun liveCookies(rows: () -> List<EncryptedCookie>) : List<HttpCookie> {
        return database.runInTransaction(Callable {
            val now = System.currentTimeMillis()
            val seen = HashSet<Pair<String?, CookieIdentity>>()
            val stale = ArrayList<EncryptedCookie>()
            val live = ArrayList<HttpCookie>()

            for (row in rows().sortedByDescending { it.id }) {
                val json = readRow(row)
                val cookie = if (json == null || !seen.add(row.host to CookieIdentity.of(json)) || json.hasExpired(now)) null else json.toHttpCookie(now)
                if (cookie == null)
                    stale.add(row)
                else
                    live.add(cookie)
            }

            deleteRows(stale)
            live.reversed()
        })
    }

    private fun deleteRows(rows: List<EncryptedCookie>) {
        val writableDb = database.openHelper.writableDatabase
        rows.forEach { writableDb.delete("encryptedcookie", "id = ?", arrayOf(it.id)) }
    }

    private fun uniqueIv() : ByteArray {
        var iv = EncryptedCache.genIV()

        //Make sure the IV is unique
        while (db.getByIv(Base64.getEncoder().encodeToString(iv)).isNotEmpty())
            iv = EncryptedCache.genIV()

        return iv
    }

    /** Re-encrypts a legacy row in place, bound to its current host and in the current format */
    private fun bindLegacyRow(row: EncryptedCookie, host: String, legacy: JSONObject) {
        val expiresAt = legacy.expiresAt()
        legacy.put(FORMAT, FORMAT_VERSION).put("expiresAt", expiresAt)

        val iv = uniqueIv()
        val values = ContentValues().apply {
            put("cookie", Base64.getEncoder().encodeToString(runAES(legacy.toString().encodeToByteArray(), iv, Cipher.ENCRYPT_MODE, host)))
            put("iv", Base64.getEncoder().encodeToString(iv))
        }
        database.openHelper.writableDatabase.update("encryptedcookie", SQLiteDatabase.CONFLICT_NONE, values, "id = ?", arrayOf(row.id))
    }

    private fun readRow(row: EncryptedCookie) : JSONObject? {
        return try {
            val iv = Base64.getDecoder().decode(row.iv ?: return null)
            val encrypted = Base64.getDecoder().decode(row.cookie ?: return null)
            val host = row.host ?: return null
            try {
                JSONObject(runAES(encrypted, iv, Cipher.DECRYPT_MODE, host).decodeToString()).takeIf { it.has("name") }
            } catch (e: AEADBadTagException) {
                //Rows written before host binding was added (no AAD, no format marker) are bound to their host on first read
                JSONObject(EncryptedCache.runAES(encrypted, iv, Cipher.DECRYPT_MODE).decodeToString())
                    .takeIf { !it.has(FORMAT) && it.has("name") }
                    ?.also { bindLegacyRow(row, host, it) }
            }
        } catch (e: AEADBadTagException) {
            null
        } catch (e: JSONException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun runAES(input: ByteArray, iv: ByteArray, cipherMode: Int, host: String) : ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(cipherMode, CacheKeyManager.getKey(), GCMParameterSpec(128, iv))
        cipher.updateAAD("$AAD_PREFIX$host".encodeToByteArray())
        return cipher.doFinal(input)
    }

    private fun uriToHost(uri: URI) : String {
        return "${uri.scheme.replace("https", "http")}://${uri.host}"
    }

    data class CookieAndId(val cookie: HttpCookie, val id: Int)

    private data class CookieIdentity(val name: String, val domain: String?, val path: String?) {
        companion object {
            fun of(cookie: HttpCookie) = CookieIdentity(
                cookie.name.lowercase(Locale.ROOT),
                cookie.domain?.lowercase(Locale.ROOT),
                cookie.path
            )

            fun of(json: JSONObject) = CookieIdentity(
                json.getString("name").lowercase(Locale.ROOT),
                json.stringOrNull("domain")?.lowercase(Locale.ROOT),
                json.stringOrNull("path")
            )
        }
    }

    private companion object {
        /** Field names match HttpCookie's, so rows serialised by older versions (via Gson) stay readable */
        fun toJson(cookie: HttpCookie, now: Long) : JSONObject = JSONObject()
            .put(FORMAT, FORMAT_VERSION)
            .put("name", cookie.name)
            .put("value", cookie.value)
            .put("comment", cookie.comment)
            .put("commentURL", cookie.commentURL)
            .put("toDiscard", cookie.discard)
            .put("domain", cookie.domain)
            .put("path", cookie.path)
            .put("portlist", cookie.portlist)
            .put("secure", cookie.secure)
            .put("httpOnly", cookie.isHttpOnly)
            .put("version", cookie.version)
            .put("expiresAt", if (cookie.maxAge < 0) null else expiry(now, cookie.maxAge))

        /** Absolute expiry in epoch millis, or null for a session cookie */
        fun JSONObject.expiresAt() : Long? = when {
            has("expiresAt") -> getLong("expiresAt")
            has(FORMAT) || optLong("maxAge", -1) < 0 -> null
            else -> expiry(optLong("whenCreated"), optLong("maxAge"))
        }

        /** [from] + [maxAgeSeconds], saturating instead of overflowing */
        fun expiry(from: Long, maxAgeSeconds: Long) : Long =
            if (maxAgeSeconds >= (Long.MAX_VALUE - from) / 1000) Long.MAX_VALUE else from + maxAgeSeconds * 1000

        fun JSONObject.hasExpired(now: Long) : Boolean = expiresAt()?.let { it <= now } ?: false

        fun JSONObject.toHttpCookie(now: Long) : HttpCookie? {
            val expiresAt = expiresAt()
            return try {
                HttpCookie(getString("name"), stringOrNull("value")).apply {
                    comment = stringOrNull("comment")
                    commentURL = stringOrNull("commentURL")
                    discard = optBoolean("toDiscard")
                    domain = stringOrNull("domain")
                    path = stringOrNull("path")
                    portlist = stringOrNull("portlist")
                    secure = optBoolean("secure")
                    isHttpOnly = optBoolean("httpOnly")
                    version = optInt("version", 1)
                    maxAge = if (expiresAt == null) -1 else (expiresAt - now - 1) / 1000 + 1
                }
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        fun JSONObject.stringOrNull(key: String) : String? = if (isNull(key)) null else getString(key)
    }
}
