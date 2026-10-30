package dev.museframework.engine.api.event

import dev.museframework.engine.api.streaming.MuseCursorIteratorProvider
import dev.museframework.engine.api.streaming.MuseCursorStreamProvider
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Represents a value with associated MIME type and encoding information.
 * This class provides a type-safe way to handle values that may or may not be present.
 */
class TypedValue<T> private constructor(
    private val _value: T?,
    val mimeType: String,
    val charset: Charset,
) {
    companion object {
        const val MIME_ANY = "*/*"
        const val MIME_JSON = "application/json"
        const val MIME_JAVA = "application/java"
        const val MIME_XML = "application/xml"
        const val MIME_TEXT = "text/plain"
        const val MIME_OCTET_STREAM = "application/octet-stream"

        @JvmStatic
        fun <T> of(value: T?): TypedValue<T> = TypedValue(value, inferMimeType(value), StandardCharsets.UTF_8)

        @JvmStatic
        fun <T> of(
            value: T?,
            mimeType: String,
        ): TypedValue<T> = TypedValue(value, mimeType, StandardCharsets.UTF_8)

        @JvmStatic
        fun <T> of(
            value: T?,
            mimeType: String,
            charset: Charset,
        ): TypedValue<T> = TypedValue(value, mimeType, charset)

        @JvmStatic
        fun <T> empty(): TypedValue<T> = TypedValue(null, MIME_ANY, StandardCharsets.UTF_8)

        @JvmStatic
        private fun <T> inferMimeType(value: T?): String {
            if (value == null) return MIME_ANY
            return when (value) {
                is ByteArray -> MIME_OCTET_STREAM
                is MuseCursorStreamProvider -> MIME_OCTET_STREAM
                is MuseCursorIteratorProvider<*> -> MIME_JAVA
                else -> MIME_JAVA
            }
        }
    }

    /**
     * The wrapped value. May be null.
     */
    val value: T?
        get() = _value

    /**
     * Returns true if a value is present, false otherwise.
     */
    fun isPresent(): Boolean = _value != null

    /**
     * Returns true if a value is not present, false otherwise.
     */
    fun isEmpty(): Boolean = _value == null

    /**
     * Returns the value if present, otherwise returns null.
     */
    fun getOrNull(): T? = _value

    /**
     * Returns the value if present, otherwise returns the provided default value.
     */
    fun getOrElse(default: T): T = _value ?: default

    /**
     * Returns the value if present, otherwise returns the result of the default function.
     */
    fun getOrElse(default: () -> T): T = _value ?: default()

    /**
     * Applies the given function to the value if present, returning a new TypedValue.
     */
    fun <R> map(transform: (T) -> R): TypedValue<R> =
        if (_value != null) {
            TypedValue(transform(_value), mimeType, charset)
        } else {
            TypedValue(null, mimeType, charset)
        }

    /**
     * Applies the given function to the value if present and flattens the result.
     */
    fun <R> flatMap(transform: (T) -> TypedValue<R>): TypedValue<R> =
        if (_value != null) {
            transform(_value)
        } else {
            TypedValue(null, mimeType, charset)
        }

    /**
     * Executes the given action if a value is present.
     */
    fun ifPresent(action: (T) -> Unit) {
        if (_value != null) {
            action(_value)
        }
    }

    /**
     * Executes the given action if a value is present, otherwise executes the empty action.
     */
    fun ifPresentOrElse(
        action: (T) -> Unit,
        emptyAction: () -> Unit,
    ) {
        if (_value != null) {
            action(_value)
        } else {
            emptyAction()
        }
    }

    /**
     * Legacy method for backward compatibility. Use getOrNull() instead.
     */
    @Deprecated("Use getOrNull() instead", ReplaceWith("getOrNull()"))
    fun unwrap(): T? = _value

    fun isStreamProvider(): Boolean = _value is MuseCursorStreamProvider || _value is MuseCursorIteratorProvider<*>

    fun asCursorStreamProvider(): MuseCursorStreamProvider? = _value as? MuseCursorStreamProvider

    @Suppress("UNCHECKED_CAST")
    fun <R> asCursorIteratorProvider(): MuseCursorIteratorProvider<R>? = _value as? MuseCursorIteratorProvider<R>

    fun withMimeType(mimeType: String): TypedValue<T> = TypedValue(_value, mimeType, charset)

    fun withEncoding(charset: Charset): TypedValue<T> = TypedValue(_value, mimeType, charset)

    override fun toString(): String = "TypedValue[value=$_value, mimeType=$mimeType, encoding=$charset]"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as TypedValue<*>
        if (_value != other._value) return false
        if (mimeType != other.mimeType) return false
        if (charset != other.charset) return false
        return true
    }

    override fun hashCode(): Int {
        var result = _value?.hashCode() ?: 0
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + charset.hashCode()
        return result
    }
}
