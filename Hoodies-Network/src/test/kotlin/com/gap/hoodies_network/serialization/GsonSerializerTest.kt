package com.gap.hoodies_network.serialization

import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal

class GsonSerializerTest {

    private val serializer: Serializer = Serializers.default

    data class Numbers(
        val i: Int = 0,
        val l: Long = 0L,
        val d: Double = 0.0,
        val f: Float = 0f,
        val big: BigDecimal? = null,
        val boxed: Int? = null
    )

    data class Nullable(val name: String? = null, val count: Int = 0, val nested: Item? = null)

    data class WithDefaults(val name: String = "default", val count: Int = 7)

    class NoDefaults(val name: String, val count: Int)

    data class Item(val id: Int, @SerializedName("item_tags") val tags: List<String>?)

    data class Wrapper<T>(val data: T?, val meta: Map<String, Any?>?)

    data class Holder(val items: List<Item>, val byKey: Map<String, List<Item>>)

    private inline fun <reified T> typeOf() = object : TypeToken<T>() {}.type

    // ---- numbers ----

    @Test
    fun numbersIntoTypedFieldsAreExact() {
        val n = serializer.fromJson<Numbers>(
            """{"i":2147483647,"l":9007199254740993,"d":0.1,"f":1.5,"big":12345678901234567890.123456789,"boxed":3}""",
            Numbers::class.java
        )!!
        assertEquals(Int.MAX_VALUE, n.i)
        assertEquals(9007199254740993L, n.l)
        assertEquals(0.1, n.d, 0.0)
        assertEquals(1.5f, n.f, 0f)
        assertEquals(BigDecimal("12345678901234567890.123456789"), n.big)
        assertEquals(3, n.boxed)
    }

    @Test
    fun wholeDoubleAndNumericStringCoerceIntoIntField() {
        assertEquals(1, serializer.fromJson<Numbers>("""{"i":1.0}""", Numbers::class.java)!!.i)
        assertEquals(42, serializer.fromJson<Numbers>("""{"i":"42"}""", Numbers::class.java)!!.i)
        assertEquals(42L, serializer.fromJson<Numbers>("""{"l":"42"}""", Numbers::class.java)!!.l)
    }

    @Test
    fun fractionalValueIntoIntFieldFails() {
        try {
            serializer.fromJson<Numbers>("""{"i":1.5}""", Numbers::class.java)
            fail("expected SerializationException")
        } catch (e: SerializationException) {
            assertTrue(e.cause is NumberFormatException || e.cause?.cause is NumberFormatException)
        }
    }

    @Test
    fun untypedNumbersBecomeDoubles() {
        val map = serializer.fromJson<Map<String, Any?>>("""{"a":1,"b":9007199254740993,"c":1.25}""", Map::class.java)!!
        assertEquals(1.0, map["a"])
        assertEquals(9.007199254740992E15, map["b"])
        assertEquals(1.25, map["c"])
        val any = serializer.fromJson<Any>("[1,2]", Any::class.java)
        assertEquals(listOf(1.0, 2.0), any)
    }

    @Test
    fun numbersSerializeUnchanged() {
        assertEquals("9223372036854775807", serializer.toJson(Long.MAX_VALUE))
        assertEquals("1.0", serializer.toJson(1.0))
        assertEquals("0.1", serializer.toJson(0.1))
        assertEquals("1.5", serializer.toJson(1.5f))
        assertEquals("12345678901234567890.123456789", serializer.toJson(BigDecimal("12345678901234567890.123456789")))
        assertEquals(
            """{"i":1,"l":2,"d":3.0,"f":4.0,"big":5,"boxed":6}""",
            serializer.toJson(Numbers(1, 2, 3.0, 4f, BigDecimal(5), 6))
        )
    }

    @Test
    fun nanIsRejected() {
        try {
            serializer.toJson(Numbers(d = Double.NaN))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("NaN"))
        }
    }

    // ---- nulls ----

    @Test
    fun nullFieldsAreOmittedOnSerialization() {
        assertEquals("""{"count":0}""", serializer.toJson(Nullable()))
        assertEquals("{}", serializer.toJson(mapOf("a" to null)))
        assertEquals("null", serializer.toJson(null))
        assertEquals("[null,1]", serializer.toJson(listOf(null, 1)))
    }

    @Test
    fun nullAndEmptyDocumentsDeserializeToNull() {
        assertNull(serializer.fromJson<Nullable>("null", Nullable::class.java))
        assertNull(serializer.fromJson<Nullable>("", Nullable::class.java))
    }

    @Test
    fun explicitNullsLeaveFieldsAtJvmDefaults() {
        val n = serializer.fromJson<Nullable>("""{"name":null,"count":null,"nested":null}""", Nullable::class.java)!!
        assertNull(n.name)
        assertEquals(0, n.count)
        assertNull(n.nested)
        val numbers = serializer.fromJson<Numbers>("""{"boxed":null,"big":null}""", Numbers::class.java)!!
        assertNull(numbers.boxed)
        assertNull(numbers.big)
    }

    @Test
    fun missingFieldsKeepKotlinDefaultsOnlyWithNoArgConstructor() {
        val withDefaults = serializer.fromJson<WithDefaults>("{}", WithDefaults::class.java)!!
        assertEquals("default", withDefaults.name)
        assertEquals(7, withDefaults.count)

        // No no-arg constructor: Gson allocates without running the constructor, so a
        // non-null Kotlin property can be null at runtime.
        val noDefaults = serializer.fromJson<NoDefaults>("""{"count":2}""", NoDefaults::class.java)!!
        assertEquals(2, noDefaults.count)
        assertNull(noDefaults.javaClass.getDeclaredField("name").apply { isAccessible = true }.get(noDefaults))
    }

    // ---- nested generics ----

    @Test
    fun nestedGenericListViaTypeToken() {
        val items = serializer.fromJson<List<Item>>(
            """[{"id":1,"item_tags":["a","b"]},{"id":2,"item_tags":null}]""",
            typeOf<List<Item>>()
        )!!
        assertEquals(listOf(Item(1, listOf("a", "b")), Item(2, null)), items)
    }

    @Test
    fun nestedGenericMapOfListsViaTypeToken() {
        val map = serializer.fromJson<Map<String, List<Item>>>(
            """{"x":[{"id":1,"item_tags":[]}],"y":[]}""",
            typeOf<Map<String, List<Item>>>()
        )!!
        assertEquals(mapOf("x" to listOf(Item(1, emptyList())), "y" to emptyList()), map)
    }

    @Test
    fun genericWrapperResolvesTypeArgument() {
        val wrapper = serializer.fromJson<Wrapper<List<Item>>>(
            """{"data":[{"id":5,"item_tags":["t"]}],"meta":{"page":1,"next":null,"tags":["z"]}}""",
            typeOf<Wrapper<List<Item>>>()
        )!!
        assertEquals(listOf(Item(5, listOf("t"))), wrapper.data)
        assertEquals(mapOf("page" to 1.0, "next" to null, "tags" to listOf("z")), wrapper.meta)
    }

    @Test
    fun genericFieldsOfConcreteClassResolve() {
        val holder = serializer.fromJson<Holder>(
            """{"items":[{"id":1,"item_tags":["a"]}],"byKey":{"k":[{"id":2,"item_tags":["b"]}]}}""",
            Holder::class.java
        )!!
        assertEquals(Item(1, listOf("a")), holder.items[0])
        assertEquals(Item(2, listOf("b")), holder.byKey.getValue("k")[0])
    }

    @Test
    fun rawCollectionClassYieldsUntypedMaps() {
        // HoodiesNetworkClient passes RESULT::class.java, so List<Item> arrives erased to List.
        val raw = serializer.fromJson<List<*>>("""[{"id":1,"item_tags":["a"]}]""", List::class.java)!!
        val first = raw[0] as Map<*, *>
        assertEquals(1.0, first["id"])
        assertEquals(listOf("a"), first["item_tags"])
    }

    @Test
    fun nestedGenericsRoundTrip() {
        val value = Wrapper(mapOf("k" to listOf(Item(1, listOf("a")))), mapOf("n" to 1))
        val json = serializer.toJson(value)
        assertEquals("""{"data":{"k":[{"id":1,"item_tags":["a"]}]},"meta":{"n":1}}""", json)
        assertEquals(value.data, serializer.fromJson<Wrapper<Map<String, List<Item>>>>(json, typeOf<Wrapper<Map<String, List<Item>>>>())!!.data)
    }

    // ---- strings / errors ----

    @Test
    fun htmlCharactersAreEscaped() {
        assertEquals("\"\\u003ca href\\u003d\\u0027x\\u0027\\u003e\\u0026\\u003c/a\\u003e\"", serializer.toJson("<a href='x'>&</a>"))
    }

    @Test
    fun malformedJsonThrowsSerializationExceptionWithParserMessage() {
        try {
            serializer.fromJson<Item>("""{"id":""", Item::class.java)
            fail("expected SerializationException")
        } catch (e: SerializationException) {
            assertTrue(e.cause is com.google.gson.JsonSyntaxException)
            assertEquals(e.cause!!.message, e.message)
        }
    }

    @Test
    fun typeMismatchThrowsSerializationException() {
        try {
            serializer.fromJson<Item>("[1]", Item::class.java)
            fail("expected SerializationException")
        } catch (e: SerializationException) {
            assertTrue(e.message!!.contains("BEGIN_OBJECT"))
        }
    }
}
