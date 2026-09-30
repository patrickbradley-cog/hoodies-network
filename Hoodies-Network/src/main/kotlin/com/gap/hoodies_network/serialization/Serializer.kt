package com.gap.hoodies_network.serialization

import java.lang.reflect.Type

/**
 * Converts request bodies to JSON and JSON response bodies to typed objects.
 *
 * Implementations must be thread-safe: a single instance is shared by every request of a client.
 */
internal interface Serializer {

    /**
     * Serializes [value] to JSON. A `null` [value] yields the JSON literal `null`.
     */
    fun toJson(value: Any?): String

    /**
     * Deserializes [json] into an instance of [type]; returns `null` for an empty document or the JSON literal `null`.
     *
     * @throws SerializationException if [json] is not well-formed or does not match [type].
     */
    @Throws(SerializationException::class)
    fun <T> fromJson(json: String, type: Type): T?
}
