package dev.museframework.connectors.objectstore.internal

import dev.museframework.engine.api.event.TypedValue
import dev.museframework.connectors.objectstore.backend.ObjectStoreBackendCorruptionException
import dev.museframework.connectors.objectstore.backend.ObjectStoreBackendKeyAlreadyExistsException
import dev.museframework.connectors.objectstore.backend.ObjectStoreBackendKeyNotFoundException
import dev.museframework.connectors.objectstore.backend.ObjectStoreBackendValueValidationException
import java.io.*
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.charset.Charset
import java.util.Collections
import java.util.IdentityHashMap

internal class ObjectStoreValueValidationException(message: String, cause: Throwable? = null) : ObjectStoreBackendValueValidationException(message, cause)
internal class ObjectStoreCorruptionException(message: String, cause: Throwable? = null) : ObjectStoreBackendCorruptionException(message, cause)
internal class ObjectStoreKeyAlreadyExistsException(key: String) : ObjectStoreBackendKeyAlreadyExistsException(key)
internal class ObjectStoreKeyNotFoundException(key: String) : ObjectStoreBackendKeyNotFoundException(key)

/** Versioned materialized values. No class names or Java deserialization are accepted. */
internal class ObjectStoreValueCodec {
    fun encode(value: TypedValue<*>): ByteArray {
        try {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.text(value.mimeType)
                output.text(value.charset.name())
                writeValue(output, value.value, Collections.newSetFromMap(IdentityHashMap()), 0)
            }
            return bytes.toByteArray()
        } catch (cause: ObjectStoreValueValidationException) { throw cause
        } catch (cause: Exception) { throw ObjectStoreValueValidationException("Cannot snapshot Object Store value", cause) }
    }

    fun decode(bytes: ByteArray): TypedValue<*> {
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                check(input.readInt() == MAGIC) { "Invalid Object Store codec magic" }
                check(input.readInt() == VERSION) { "Unsupported Object Store codec version" }
                val mimeType = input.text()
                val charset = Charset.forName(input.text())
                val value = readValue(input, 0)
                check(input.available() == 0) { "Trailing Object Store value bytes" }
                return TypedValue.of(value, mimeType, charset)
            }
        } catch (cause: Exception) { throw ObjectStoreCorruptionException("Invalid stored Object Store value", cause) }
    }

    private fun writeValue(output: DataOutputStream, value: Any?, active: MutableSet<Any>, depth: Int) {
        if (depth > MAX_DEPTH) throw ObjectStoreValueValidationException("Object Store value nesting exceeds $MAX_DEPTH")
        when (value) {
            null -> output.writeByte(0)
            is String -> { output.writeByte(1); output.text(value) }
            is Boolean -> { output.writeByte(2); output.writeBoolean(value) }
            is Byte -> { output.writeByte(3); output.writeByte(value.toInt()) }
            is Short -> { output.writeByte(4); output.writeShort(value.toInt()) }
            is Int -> { output.writeByte(5); output.writeInt(value) }
            is Long -> { output.writeByte(6); output.writeLong(value) }
            is Float -> { requireFinite(value.isFinite()); output.writeByte(7); output.writeFloat(value) }
            is Double -> { requireFinite(value.isFinite()); output.writeByte(8); output.writeDouble(value) }
            is BigInteger -> { output.writeByte(9); output.text(value.toString()) }
            is BigDecimal -> { output.writeByte(10); output.text(value.toString()) }
            is ByteArray -> { output.writeByte(11); output.writeInt(value.size); output.write(value) }
            is List<*> -> container(value, active) {
                output.writeByte(12); output.writeInt(value.size)
                value.forEach { writeValue(output, it, active, depth + 1) }
            }
            is Map<*, *> -> container(value, active) {
                output.writeByte(13); output.writeInt(value.size)
                value.forEach { (key, item) ->
                    if (key !is String) throw ObjectStoreValueValidationException("Object Store map keys must be strings")
                    output.text(key); writeValue(output, item, active, depth + 1)
                }
            }
            else -> throw ObjectStoreValueValidationException("Unsupported Object Store value type: ${value.javaClass.name}")
        }
    }

    private inline fun container(value: Any, active: MutableSet<Any>, action: () -> Unit) {
        if (!active.add(value)) throw ObjectStoreValueValidationException("Cyclic Object Store value")
        try { action() } finally { active.remove(value) }
    }

    private fun requireFinite(finite: Boolean) {
        if (!finite) throw ObjectStoreValueValidationException("Object Store numeric values must be finite")
    }

    private fun readValue(input: DataInputStream, depth: Int): Any? {
        check(depth <= MAX_DEPTH) { "Object Store value nesting exceeds $MAX_DEPTH" }
        return when (val tag = input.readUnsignedByte()) {
            0 -> null
            1 -> input.text()
            2 -> when (input.readUnsignedByte()) { 0 -> false; 1 -> true; else -> error("Invalid boolean") }
            3 -> input.readByte()
            4 -> input.readShort()
            5 -> input.readInt()
            6 -> input.readLong()
            7 -> input.readFloat().also { check(it.isFinite()) }
            8 -> input.readDouble().also { check(it.isFinite()) }
            9 -> BigInteger(input.text())
            10 -> BigDecimal(input.text())
            11 -> ByteArray(input.length()).also(input::readFully)
            12 -> Collections.unmodifiableList(List(input.length()) { readValue(input, depth + 1) })
            13 -> {
                val result = linkedMapOf<String, Any?>()
                repeat(input.length()) {
                    val key = input.text()
                    check(!result.containsKey(key)) { "Duplicate encoded map key" }
                    result[key] = readValue(input, depth + 1)
                }
                Collections.unmodifiableMap(result)
            }
            else -> error("Unknown Object Store value tag $tag")
        }
    }

    private fun DataOutputStream.text(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size); write(bytes)
    }

    private fun DataInputStream.length(): Int = readInt().also {
        check(it >= 0 && it <= available()) { "Invalid Object Store value length" }
    }

    private fun DataInputStream.text(): String = ByteArray(length()).also(::readFully).toString(Charsets.UTF_8)

    companion object {
        const val VERSION = 1
        private const val MAGIC = 0x4d4f5356
        private const val MAX_DEPTH = 256
    }
}
