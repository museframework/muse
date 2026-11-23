package dev.museframework.babelfish.emitter

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.buildCodeBlock

/** Emits a Kotlin string expression that reproduces [value] exactly. */
fun kotlinStringLiteral(value: String): CodeBlock {
    if ('\n' !in value) return CodeBlock.of("%S", value)

    // KotlinPoet's multiline %S uses trimMargin(), which normalizes CR and CRLF.
    return buildCodeBlock {
        add("(")
        value.splitToSequence('\n').forEachIndexed { index, chunk ->
            if (index > 0) add(" + \"\\n\" + ")
            add("%S", chunk)
        }
        add(")")
    }
}

/** Emits a locale-independent Kotlin numeric expression that preserves the source number type. */
fun kotlinNumericLiteral(value: Number): CodeBlock =
    when (value) {
        is Byte -> CodeBlock.of("(%L).toByte()", value.toString())
        is Short -> CodeBlock.of("(%L).toShort()", value.toString())
        is Int -> CodeBlock.of("%L", value.toString())
        is Long ->
            if (value == Long.MIN_VALUE) {
                CodeBlock.of("%T.MIN_VALUE", Long::class)
            } else {
                CodeBlock.of("%L", "${value}L")
            }
        is Float ->
            if (value.isFinite()) {
                CodeBlock.of("%L", "${value}F")
            } else {
                CodeBlock.of("%T.fromBits(%L)", Float::class, value.toRawBits().toString())
            }
        is Double ->
            if (value.isFinite()) {
                CodeBlock.of("%L", value.toString())
            } else {
                CodeBlock.of("%T.fromBits(%L)", Double::class, "${value.toRawBits()}L")
            }
        else -> throw IllegalArgumentException("Unsupported Kotlin numeric literal type: ${value::class.java.name}")
    }
