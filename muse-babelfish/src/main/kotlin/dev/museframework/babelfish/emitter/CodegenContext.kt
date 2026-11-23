package dev.museframework.babelfish.emitter

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.NameAllocator
import com.squareup.kotlinpoet.buildCodeBlock
import dev.museframework.babelfish.exception.MissingEmitterException
import dev.museframework.dsl.api.model.component.PipelineElement
import dev.museframework.dsl.api.model.component.Source
import dev.museframework.dsl.api.model.config.MuseConfiguration
import java.nio.file.Path

class CodegenContext internal constructor(
    val packageName: String,
    private val sourceFile: Path?,
    private val elementEmitters: Map<String, ElementCodeEmitter<*>>,
) {
    constructor(packageName: String) : this(packageName, null, emptyMap())

    private val names = NameAllocator()
    private val configNames = mutableMapOf<String, String>()
    private val flowNames = mutableMapOf<String, String>()
    internal var currentFlow: String? = null

    internal fun reserveProviderNames() {
        names.newName("configs")
        names.newName("flows")
    }

    fun configValName(muleId: String): String =
        configNames.getOrPut(muleId) { names.newName(propertyName(muleId, "config")) }

    fun flowValName(flowName: String): String =
        flowNames.getOrPut(flowName) { names.newName(propertyName(flowName, "flow")) }

    private fun propertyName(value: String, fallback: String): String =
        value.split(IDENTIFIER_SEPARATORS)
            .filter(String::isNotEmpty)
            .mapIndexed { index, part ->
                if (index == 0) part.lowercase() else part.lowercase().replaceFirstChar(Char::uppercaseChar)
            }.joinToString("").ifEmpty { fallback }

    /** Emit the child calls of a router or error strategy in execution order. */
    fun emitPipelineElements(elements: List<PipelineElement>): CodeBlock =
        buildCodeBlock {
            for (element in elements) {
                @Suppress("UNCHECKED_CAST")
                val emitter = elementEmitters[element::class.qualifiedName] as? ElementCodeEmitter<PipelineElement>
                    ?: missingEmitter("element", element)
                add("%L\n", emitter.emit(element, this@CodegenContext))
            }
        }

    internal fun missingEmitter(category: String, model: Any): Nothing =
        throw MissingEmitterException(category, model.javaClass, sourceFile, currentFlow)
}

internal val IDENTIFIER_SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

fun interface ElementCodeEmitter<in T : PipelineElement> {
    fun emit(
        element: T,
        ctx: CodegenContext,
    ): CodeBlock
}

fun interface SourceCodeEmitter<in T : Source> {
    fun emit(
        source: T,
        ctx: CodegenContext,
    ): CodeBlock
}

fun interface ConfigCodeEmitter<in T : MuseConfiguration> {
    fun emit(
        config: T,
        ctx: CodegenContext,
    ): CodeBlock
}
