package dev.museframework.babelfish.strategy

import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.dsl.api.model.component.PipelineElement
import com.squareup.kotlinpoet.FileSpec

/**
 * KotlinPoet-aware code generation context that extends [CodegenContext]
 * with [FileSpec.Builder] integration for automatic import management.
 *
 * This context delegates import tracking to KotlinPoet's FileSpec.Builder,
 * which automatically aggregates imports during code generation. The builder
 * is passed in the constructor and shared across the code generation process.
 *
 * Example usage:
 * ```kotlin
 * val fileSpecBuilder = FileSpec.builder(packageName, className)
 * val ctx = PoetCodegenContext(packageName, fileSpecBuilder)
 * ctx.addImport("dev.museframework.dsl.dsl.setPayload")
 * // ... emit code using ctx ...
 * val fileSpec = fileSpecBuilder.build()  // Imports auto-aggregated
 * ```
 *
 * @param packageName The package name for the generated code
 * @param fileSpecBuilder The KotlinPoet FileSpec.Builder for import aggregation
 *
 * @see CodegenContext
 * @see FileSpec.Builder
 */
class PoetCodegenContext(
    packageName: String,
    private val fileSpecBuilder: FileSpec.Builder,
) : CodegenContext(packageName) {

    /**
     * Adds an import to the FileSpec.Builder.
     *
     * Delegates to [FileSpec.Builder.addImport] for automatic import aggregation.
     * Supports both simple class names (resolved against the package) and
     * fully-qualified names.
     *
     * @param fqn The fully-qualified name of the class to import
     */
    override fun addImport(fqn: String) {
        // Parse the FQN to extract package and class name for KotlinPoet
        val lastDotIndex = fqn.lastIndexOf('.')
        if (lastDotIndex > 0) {
            val importPackage = fqn.substring(0, lastDotIndex)
            val className = fqn.substring(lastDotIndex + 1)
            fileSpecBuilder.addImport(importPackage, className)
        }
        // Also maintain parent class tracking for backward compatibility
        super.addImport(fqn)
    }

    /**
     * Returns the list of resolved imports from the FileSpec.Builder.
     *
     * Note: The imports are only fully resolved after [FileSpec.Builder.build]
     * is called. This method returns the imports tracked by the parent
     * [CodegenContext] for backward compatibility.
     *
     * @return List of fully-qualified import names, sorted alphabetically
     */
    override fun resolvedImports(): List<String> {
        // Return parent imports for backward compatibility
        // FileSpec.Builder imports are accessed via fileSpecBuilder.build().imports
        return super.resolvedImports()
    }

    /**
     * Gets the FileSpec.Builder for direct access when needed.
     *
     * This allows emitters to add imports directly to the builder if needed,
     * though using [addImport] is the preferred approach.
     *
     * @return The FileSpec.Builder instance
     */
    fun getFileSpecBuilder(): FileSpec.Builder = fileSpecBuilder

    /**
     * Emits a list of PipelineElements using the registered element emitters.
     *
     * This method recursively emits child elements by looking up the appropriate
     * emitter for each element type. It's used by router emitters to emit
     * their nested processors and routers.
     *
     * The emission delegates to parent [CodegenContext.emitPipelineElements]
     * while maintaining access to the FileSpec.Builder for import tracking.
     *
     * @param elements The list of pipeline elements to emit
     * @param indent The indentation string to prepend to each emitted element
     * @return The concatenated emitted code for all elements
     */
    override fun emitPipelineElements(
        elements: List<PipelineElement>,
        indent: String,
    ): String {
        return buildString {
            elements.forEach { el ->
                val emitter = getElementEmitter(el)
                if (emitter != null) {
                    @Suppress("UNCHECKED_CAST")
                    val code = (emitter as ElementCodeEmitter<Any>).emit(el, this@PoetCodegenContext)
                    appendLine(code.prependIndent(indent))
                }
            }
        }
    }

    /**
     * Escapes a string for use in Kotlin double-quoted string literals.
     *
     * For flow names, subFlow refs, etc. that use "..." (not triple quotes).
     * Does NOT escape control chars - those should not appear in identifiers.
     *
     * Escapes: backslash, double quote, dollar sign
     *
     * @param value The string to escape
     * @return The escaped string safe for double-quoted Kotlin literals
     */
    override fun escapeKotlinString(value: String): String {
        return super.escapeKotlinString(value)
    }

    /**
     * Escapes a string for use in Kotlin triple-quoted string literals ("""...""")
     *
     * Only escapes backslash and dollar sign. Double quotes do not need
     * escaping in triple-quoted strings.
     *
     * @param value The string to escape
     * @return The escaped string safe for triple-quoted Kotlin literals
     */
    override fun escapeTripleQuotedString(value: String): String {
        return super.escapeTripleQuotedString(value)
    }
}
