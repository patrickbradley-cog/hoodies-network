package com.gap.hoodies_network.serialization

/**
 * Library-wide default [Serializer] (Gson). Replacing the implementation, e.g. with kotlinx.serialization,
 * does not change the public API.
 */
internal object Serializers {

    val default: Serializer = GsonSerializer()
}
