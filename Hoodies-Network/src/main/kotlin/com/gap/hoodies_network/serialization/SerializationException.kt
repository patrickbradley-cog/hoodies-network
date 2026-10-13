package com.gap.hoodies_network.serialization

/**
 * Thrown by a [Serializer] when a JSON document is malformed or does not match the requested type.
 * [message] is the underlying parser's message, unchanged.
 */
internal class SerializationException(message: String?, cause: Throwable?) : RuntimeException(message, cause)
