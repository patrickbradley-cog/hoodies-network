package com.gap.hoodies_network.serialization

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.lang.reflect.Type

/**
 * [Serializer] backed by Gson. The default instance uses a plain `Gson()` so that request bodies,
 * response parsing and stored cookie JSON stay byte-for-byte identical to the pre-abstraction behaviour.
 */
internal class GsonSerializer(val gson: Gson = Gson()) : Serializer {

    override fun toJson(value: Any?): String = gson.toJson(value)

    override fun <T> fromJson(json: String, type: Type): T? =
        try {
            gson.fromJson<T>(json, type)
        } catch (error: JsonSyntaxException) {
            throw SerializationException(error.message, error)
        }
}
