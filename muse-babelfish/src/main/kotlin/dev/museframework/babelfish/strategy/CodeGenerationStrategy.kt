package dev.museframework.babelfish.strategy

import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.mapper.TranslationResult
import kotlin.reflect.KClass

/**
 * Strategy interface for code generation from MuleSoft XML to Kotlin DSL.
 *
 * This interface abstracts the code generation process, allowing different implementations
 * to use various approaches (e.g., string-based building, KotlinPoet type-safe generation).
 *
 * Implementations must handle:
 * - Both @MuseConfigComponent (regular) and MuseConfiguration (native) modes
 * - Emitter registration for different component types
 * - Context creation for managing imports and nested element emission
 *
 * @see dev.museframework.babelfish.emitter.KotlinCodeEmitter for the current implementation
 */
interface CodeGenerationStrategy {

    /**
     * Generates a complete Kotlin source file from a translation result.
     *
     * @param result The translation result containing flows, configs, and metadata
     * @param packageName The target package name for the generated class
     * @param nativeBuild If true, generates MuseConfiguration-based code (reflection-free);
     *                    if false, generates @MuseConfigComponent-based code
     * @return The complete Kotlin source code as a string
     */
    fun generateFile(
        result: TranslationResult,
        packageName: String,
        nativeBuild: Boolean = false,
    ): String

    /**
     * Creates a new code generation context for managing imports and emission state.
     *
     * The context is used throughout the generation process to:
     * - Track and resolve imports
     * - Manage variable naming for configs and flows
     * - Provide emitter lookup for nested elements
     * - Handle string escaping for Kotlin literals
     *
     * @param packageName The package name for the generated code
     * @return A new [CodegenContext] instance configured for this strategy
     */
    fun createContext(packageName: String): CodegenContext

    /**
     * Registers an emitter for a specific component type.
     *
     * Emitters are responsible for converting DSL model objects into Kotlin code strings.
     * The emitter type is determined by the component type it handles:
     * - [dev.museframework.dsl.api.model.config.MuseConfiguration] → ConfigCodeEmitter
     * - [dev.museframework.dsl.api.model.component.Source] → SourceCodeEmitter
     * - [dev.museframework.dsl.api.model.component.PipelineElement] → ElementCodeEmitter
     *
     * @param type The Kotlin class of the component type this emitter handles
     * @param emitter The emitter instance (type will be checked at runtime)
     * @throws IllegalArgumentException if the emitter type doesn't match the component type
     */
    fun registerEmitter(
        type: KClass<*>,
        emitter: Any,
    )
}
