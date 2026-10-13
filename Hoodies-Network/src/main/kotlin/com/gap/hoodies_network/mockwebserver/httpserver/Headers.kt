package com.sun.net.httpserver

import com.gap.hoodies_network.utils.Generated

/**
 * Source- and binary-compatible replacement for the `com.sun.net.httpserver.Headers` class that
 * used to be provided by the bundled `http-2.2.1.jar`.
 *
 * Keys are normalized like the JDK implementation: the first character is upper-cased and the
 * remaining characters are lower-cased, which makes lookups case-insensitive.
 */
@Generated
open class Headers : MutableMap<String, MutableList<String>> {
    private val map = HashMap<String, MutableList<String>>(32)

    override val size: Int
        get() = map.size

    override val keys: MutableSet<String>
        get() = map.keys

    override val values: MutableCollection<MutableList<String>>
        get() = map.values

    override val entries: MutableSet<MutableMap.MutableEntry<String, MutableList<String>>>
        get() = map.entries

    override fun isEmpty(): Boolean = map.isEmpty()

    override fun containsKey(key: String): Boolean = map.containsKey(normalize(key))

    override fun containsValue(value: MutableList<String>): Boolean = map.containsValue(value)

    override fun get(key: String): MutableList<String>? = map[normalize(key)]

    /**
     * Returns the first value for [key], or null if there is none
     */
    fun getFirst(key: String): String? = map[normalize(key)]?.firstOrNull()

    override fun put(key: String, value: MutableList<String>): MutableList<String>? =
        map.put(normalize(key), value)

    /**
     * Adds [value] to the list of values for [key]
     */
    fun add(key: String, value: String) {
        map.getOrPut(normalize(key)) { mutableListOf() }.add(value)
    }

    /**
     * Replaces all values for [key] with the single [value]
     */
    operator fun set(key: String, value: String) {
        map[normalize(key)] = mutableListOf(value)
    }

    override fun remove(key: String): MutableList<String>? = map.remove(normalize(key))

    override fun putAll(from: Map<out String, MutableList<String>>) {
        for ((key, value) in from) put(key, value)
    }

    override fun clear() = map.clear()

    override fun equals(other: Any?): Boolean = map == other

    override fun hashCode(): Int = map.hashCode()

    override fun toString(): String = map.toString()

    private fun normalize(key: String): String {
        if (key.isEmpty()) return key
        val chars = key.toCharArray()
        for (i in chars.indices) {
            val c = chars[i]
            require(c != '\r' && c != '\n') { "illegal character in key" }
            chars[i] = if (i == 0) {
                if (c in 'a'..'z') c - 32 else c
            } else {
                if (c in 'A'..'Z') c + 32 else c
            }
        }
        return String(chars)
    }
}
