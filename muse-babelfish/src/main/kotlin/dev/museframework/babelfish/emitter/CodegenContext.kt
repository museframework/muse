package dev.museframework.babelfish.emitter

import dev.museframework.dsl.api.model.component.PipelineElement
import dev.museframework.dsl.api.model.component.Source
import dev.museframework.dsl.api.model.config.MuseConfiguration
import kotlin.reflect.KClass

open class CodegenContext(
    val packageName: String,
) {
    private val imports = mutableSetOf<String>()
    internal var elementEmitters: Map<String, ElementCodeEmitter<*>> = emptyMap()

    open fun addImport(fqn: String) {
        imports.add(fqn)
    }

    open fun resolvedImports(): List<String> = imports.sorted()

    fun configValName(muleId: String): String =
        muleId
            .split("_", "-", " ")
            .filter { it.isNotEmpty() }
            .mapIndexed { i, part ->
                if (i == 0) {
                    part.lowercase()
                } else {
                    part.lowercase().replaceFirstChar { it.uppercase() }
                }
            }.joinToString("")

    fun flowValName(flowName: String): String =
        flowName
            .split("-", "_", " ", ":", "\\", "/", ".")
            .filter { it.isNotEmpty() }
            .mapIndexed { i, part ->
                if (i == 0) {
                    part.lowercase()
                } else {
                    part.lowercase().replaceFirstChar { it.uppercase() }
                }
            }.joinToString("")

    /**
     * Look up the emitter for a PipelineElement by its qualified class name.
     * Uses class name instead of KClass to avoid classloader issues.
     */
    fun getElementEmitter(element: PipelineElement): ElementCodeEmitter<*>? = elementEmitters[element::class.qualifiedName]

    /**
     * Emit a list of PipelineElements, delegating to registered element emitters.
     * Used by router emitters to recursively emit child elements.
     */
    open fun emitPipelineElements(
        elements: List<PipelineElement>,
        indent: String = "        ",
    ): String =
        buildString {
            elements.forEach { el ->
                val emitter = getElementEmitter(el)
                if (emitter != null) {
                    @Suppress("UNCHECKED_CAST")
                    val code = (emitter as ElementCodeEmitter<Any>).emit(el, this@CodegenContext)
                    appendLine(code.prependIndent(indent))
                }
            }
        }

    /**
     * Escapes a string for use in Kotlin double-quoted string literals.
     * For flow names, subFlow refs, etc. that use "..." (not triple quotes).
     * Does NOT escape control chars - those should not appear in identifiers.
     */
    open fun escapeKotlinString(value: String): String {
        return value
            .replace("\\", "\\\\")  // Backslash first!
            .replace("\"", "\\\"")   // Double quote
            .replace("$", "\\$")   // Dollar sign
    }

    /**
     * Escapes for triple-quoted strings (""".*"""): only backslash and dollar.
     */
    open fun escapeTripleQuotedString(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("$", "\\$")
    }
}

fun interface ElementCodeEmitter<in T : PipelineElement> {
    fun emit(
        element: T,
        ctx: CodegenContext,
    ): String
}

fun interface SourceCodeEmitter<in T : Source> {
    fun emit(
        source: T,
        ctx: CodegenContext,
    ): String
}

fun interface ConfigCodeEmitter<in T : MuseConfiguration> {
    fun emit(
        config: T,
        ctx: CodegenContext,
    ): String
}
