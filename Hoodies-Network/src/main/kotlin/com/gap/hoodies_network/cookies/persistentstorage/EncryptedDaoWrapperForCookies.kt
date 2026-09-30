package com.gap.hoodies_network.cookies.persistentstorage

import android.content.Context
import androidx.room.Room
import com.gap.hoodies_network.cache.EncryptedCache
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type
import java.net.HttpCookie
import java.net.URI
import java.util.*
import javax.crypto.Cipher

class EncryptedDaoWrapperForCookies(instanceName: String, context: Context) {
    private val db = Room.databaseBuilder(context, EncryptedCookieDatabase::class.java, instanceName).build().encryptedCookieDao()
    private val gson = GsonBuilder()
        .registerTypeAdapter(HttpCookie::class.java, HttpCookieJsonAdapter)
        .create()

    fun getAll() : List<HttpCookie> {
        return db.getAll().map{ decryptCookie(it).cookie }.toList()
    }

    fun getByHost(host: URI) : List<HttpCookie> {
        return db.getByHost(uriToHost(host)).map{ decryptCookie(it).cookie }.toList()
    }

    private fun decryptCookie(encryptedCookie: EncryptedCookie) : CookieAndId {
        val iv = Base64.getDecoder().decode(encryptedCookie.iv)
        val decryptedCookieJson = EncryptedCache.runAES(Base64.getDecoder().decode(encryptedCookie.cookie), iv, Cipher.DECRYPT_MODE).decodeToString()

        return CookieAndId(gson.fromJson(decryptedCookieJson, HttpCookie::class.java), encryptedCookie.id)
    }

    fun deleteAll() {
        db.deleteAll()
    }

    fun deleteCookie(cookie: HttpCookie) : Boolean {
        return db.deleteByHash(cookie.hashCode()) > 0
    }

    fun getAllHosts() : List<URI> {
        return db.getAllHosts().map{ URI(it) }.toList()
    }

    fun insert(host: URI, cookie: HttpCookie) {
        val cookieJson = gson.toJson(cookie)
        var iv = EncryptedCache.genIV()

        //Make sure the IV is unique
        while (db.getByIv(Base64.getEncoder().encodeToString(iv)).isNotEmpty())
            iv = EncryptedCache.genIV()

        val encryptedCookieJson = Base64.getEncoder().encodeToString(EncryptedCache.runAES(cookieJson.encodeToByteArray(), iv, Cipher.ENCRYPT_MODE))

        db.insert(EncryptedCookie(0, uriToHost(host), encryptedCookieJson, Base64.getEncoder().encodeToString(iv), cookie.hashCode()))
    }

    private fun uriToHost(uri: URI) : String {
        return "${uri.scheme.replace("https", "http")}://${uri.host}"
    }

    data class CookieAndId(val cookie: HttpCookie, val id: Int)
}

/**
 * Reflective field dumps of `java.net.HttpCookie` are unreliable: on API 35 the
 * platform's hidden-API policy filters most of its private fields out of
 * reflection, so `Gson()` silently serializes only `httpOnly` and `whenCreated`
 * and every cookie reads back with a null name. This adapter uses only the
 * public getters/setters and emits the same JSON keys the old reflective dump
 * produced, so rows encrypted by older app versions still decode.
 */
internal object HttpCookieJsonAdapter : JsonSerializer<HttpCookie>, JsonDeserializer<HttpCookie> {
    override fun serialize(src: HttpCookie, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        return JsonObject().apply {
            src.comment?.let { addProperty("comment", it) }
            src.commentURL?.let { addProperty("commentURL", it) }
            addProperty("toDiscard", src.discard)
            src.domain?.let { addProperty("domain", it) }
            addProperty("maxAge", src.maxAge)
            addProperty("name", src.name)
            src.path?.let { addProperty("path", it) }
            src.portlist?.let { addProperty("portlist", it) }
            addProperty("secure", src.secure)
            addProperty("httpOnly", src.isHttpOnly)
            addProperty("value", src.value)
            addProperty("version", src.version)
        }
    }

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): HttpCookie {
        val obj = json.asJsonObject
        val cookie = HttpCookie(obj.stringOrNull("name"), obj.stringOrNull("value"))
        obj.stringOrNull("comment")?.let { cookie.comment = it }
        obj.stringOrNull("commentURL")?.let { cookie.commentURL = it }
        // Rows written by the old reflective dump use the field name "toDiscard".
        obj.booleanOrNull("toDiscard")?.let { cookie.discard = it }
        obj.stringOrNull("domain")?.let { cookie.domain = it }
        obj.longOrNull("maxAge")?.let { cookie.maxAge = it }
        obj.stringOrNull("path")?.let { cookie.path = it }
        obj.stringOrNull("portlist")?.let { cookie.portlist = it }
        obj.booleanOrNull("secure")?.let { cookie.secure = it }
        obj.booleanOrNull("httpOnly")?.let { cookie.isHttpOnly = it }
        obj.intOrNull("version")?.let { cookie.version = it }
        return cookie
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeUnless { it is JsonNull }?.asString

    private fun JsonObject.booleanOrNull(key: String): Boolean? =
        get(key)?.takeUnless { it is JsonNull }?.asBoolean

    private fun JsonObject.longOrNull(key: String): Long? =
        get(key)?.takeUnless { it is JsonNull }?.asLong

    private fun JsonObject.intOrNull(key: String): Int? =
        get(key)?.takeUnless { it is JsonNull }?.asInt
}
