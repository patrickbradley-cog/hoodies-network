package com.gap.hoodies_network.cookies.persistentstorage

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpCookie

/**
 * JSON codec for persisted cookies. The on-disk format is the one Gson produced by reflecting over
 * java.net.HttpCookie's fields up to API 34. From API 35 HttpCookie no longer declares those fields,
 * so the attributes are written and read explicitly under the same keys.
 */
internal object HttpCookieJson {
    private val gson = Gson()

    fun toJson(cookie: HttpCookie): String {
        val json = gson.toJsonTree(cookie).asJsonObject
        json.putIfNotNull("name", cookie.name)
        json.putIfNotNull("value", cookie.value)
        json.putIfNotNull("comment", cookie.comment)
        json.putIfNotNull("commentURL", cookie.commentURL)
        json.putIfNotNull("domain", cookie.domain)
        json.putIfNotNull("path", cookie.path)
        json.putIfNotNull("portlist", cookie.portlist)
        json.addProperty("maxAge", cookie.maxAge)
        json.addProperty("secure", cookie.secure)
        json.addProperty("httpOnly", cookie.isHttpOnly)
        json.addProperty("toDiscard", cookie.discard)
        json.addProperty("version", cookie.version)
        return gson.toJson(json)
    }

    fun fromJson(json: String): HttpCookie {
        val reflected = gson.fromJson(json, HttpCookie::class.java)
        if (reflected.name != null) return reflected

        val obj = JsonParser.parseString(json).asJsonObject
        val name = obj.string("name") ?: return reflected
        val cookie = try {
            HttpCookie(name, obj.string("value"))
        } catch (e: IllegalArgumentException) {
            return reflected
        }
        cookie.comment = obj.string("comment")
        cookie.commentURL = obj.string("commentURL")
        obj.string("domain")?.let { cookie.domain = it }
        cookie.path = obj.string("path")
        cookie.portlist = obj.string("portlist")
        obj.get("maxAge")?.let { cookie.maxAge = it.asLong }
        obj.get("secure")?.let { cookie.secure = it.asBoolean }
        obj.get("httpOnly")?.let { cookie.isHttpOnly = it.asBoolean }
        obj.get("toDiscard")?.let { cookie.discard = it.asBoolean }
        obj.get("version")?.let { cookie.version = it.asInt }
        copyWhenCreated(reflected, cookie)
        return cookie
    }

    private fun copyWhenCreated(from: HttpCookie, to: HttpCookie) {
        try {
            val field = HttpCookie::class.java.getDeclaredField("whenCreated")
            field.isAccessible = true
            field.setLong(to, field.getLong(from))
        } catch (e: ReflectiveOperationException) {
            // Keep the construction time if the platform does not expose the field.
        }
    }

    private fun JsonObject.putIfNotNull(key: String, value: String?) {
        if (value != null) addProperty(key, value)
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeUnless { it.isJsonNull }?.asString
}
