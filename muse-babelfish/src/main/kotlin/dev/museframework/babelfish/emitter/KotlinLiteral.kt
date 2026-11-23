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
