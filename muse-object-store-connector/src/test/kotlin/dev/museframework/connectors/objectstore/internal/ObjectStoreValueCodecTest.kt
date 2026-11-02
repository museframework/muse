package dev.museframework.connectors.objectstore.internal

import dev.museframework.engine.api.event.TypedValue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import kotlin.test.*

class ObjectStoreValueCodecTest {
    private val codec = ObjectStoreValueCodec()

    @Test
    fun `scalar tags and typed metadata survive roundtrip`() {
        val values = listOf("text", true, 1.toByte(), 2.toShort(), 3, 4L, 1.25f, 2.5,
            BigInteger("12345678901234567890"), BigDecimal("1.2300"))
        values.forEach { value ->
            val decoded = codec.decode(codec.encode(TypedValue.of(value, "application/custom", StandardCharsets.UTF_16LE)))
            assertEquals(value, decoded.value)
            assertEquals(value.javaClass, decoded.value!!::class.java)
            assertEquals("application/custom", decoded.mimeType)
            assertEquals(StandardCharsets.UTF_16LE, decoded.charset)
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `encoding snapshots mutable input and decoding returns detached values`() {
        val bytes = byteArrayOf(1, 2)
        val list = mutableListOf<Any?>(bytes, null)
        val map = linkedMapOf("items" to list)
        val encoded = codec.encode(TypedValue.of(map))
        bytes[0] = 9
        list.clear()
        val decoded = codec.decode(encoded).value as Map<*, *>
        val items = decoded["items"] as List<*>
        assertContentEquals(byteArrayOf(1, 2), items[0] as ByteArray)
        assertNull(items[1])
        (items[0] as ByteArray)[0] = 7
        assertContentEquals(byteArrayOf(1, 2), ((codec.decode(encoded).value as Map<*, *>)["items"] as List<*>)[0] as ByteArray)
        assertFailsWith<UnsupportedOperationException> { (items as MutableList<Any?>).add("bad") }
    }

    @Test
    fun `unsupported streams objects map keys cycles and nonfinite numbers fail validation`() {
        val cycle = mutableListOf<Any?>().also { it.add(it) }
        listOf(ByteArrayInputStream(byteArrayOf()), Any(), mapOf(1 to "value"), cycle, Double.NaN, Float.POSITIVE_INFINITY).forEach {
            assertFailsWith<ObjectStoreValueValidationException> { codec.encode(TypedValue.of(it)) }
        }
    }

    @Test
    fun `corrupt or unknown codec is rejected`() {
        assertFailsWith<ObjectStoreCorruptionException> { codec.decode(byteArrayOf(1, 2, 3)) }
        val bytes = codec.encode(TypedValue.of("valid"))
        bytes[7] = 99
        assertFailsWith<ObjectStoreCorruptionException> { codec.decode(bytes) }
    }
}
