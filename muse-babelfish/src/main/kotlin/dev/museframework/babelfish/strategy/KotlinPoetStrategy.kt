package dev.museframework.babelfish.strategy

import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ConfigCodeEmitter
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.babelfish.emitter.SourceCodeEmitter
import dev.museframework.babelfish.mapper.TranslationResult
import dev.museframework.dsl.api.config.AsyncConfig
import dev.museframework.dsl.api.config.ChoiceConfig
import dev.museframework.dsl.api.config.ConfigurationPropertiesConfig
import dev.museframework.dsl.api.config.ForEachConfig
import dev.museframework.dsl.api.config.LoggerConfig
import dev.museframework.dsl.api.config.RaiseErrorConfig
import dev.museframework.dsl.api.config.ScatterGatherConfig
import dev.museframework.dsl.api.config.SetPayloadConfig
import dev.museframework.dsl.api.config.SetVariableConfig
import dev.museframework.dsl.api.config.TryConfig
import dev.museframework.dsl.api.config.TransformConfig
import dev.museframework.dsl.api.config.UntilSuccessfulConfig
import dev.museframework.dsl.api.config.WithCorrelationIdConfig
import dev.museframework.dsl.api.model.component.PipelineElement
import dev.museframework.dsl.api.model.component.Source
import dev.museframework.dsl.api.model.config.MuseConfiguration
import dev.museframework.dsl.api.model.flow.FlowRefConfig
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.TypeSpec
import kotlin.reflect.KClass

/**
 * KotlinPoet-based implementation of [CodeGenerationStrategy].
 *
 * This strategy uses KotlinPoet's type-safe API to generate Kotlin source code
 * from MuleSoft XML translation results. It provides a more robust and
 * maintainable alternative to string-based code generation.
 *
 * @see com.squareup.kotlinpoet.FileSpec
 * @see com.squareup.kotlinpoet.TypeSpec
 */
class KotlinPoetStrategy : CodeGenerationStrategy {

    private val configEmitters = mutableMapOf<KClass<*>, ConfigCodeEmitter<*>>()
    private val sourceEmitters = mutableMapOf<KClass<*>, SourceCodeEmitter<*>>()
    private val elementEmitters = mutableMapOf<KClass<*>, ElementCodeEmitter<*>>()

    init {
        registerEmitter(SetPayloadConfig::class, KotlinPoetSetPayloadCodeEmitter)
        registerEmitter(SetVariableConfig::class, KotlinPoetSetVariableCodeEmitter)
        registerEmitter(FlowRefConfig::class, KotlinPoetFlowRefCodeEmitter)
        registerEmitter(RaiseErrorConfig::class, KotlinPoetRaiseErrorCodeEmitter)
        registerEmitter(LoggerConfig::class, KotlinPoetLoggerCodeEmitter)
        registerEmitter(ConfigurationPropertiesConfig::class, KotlinPoetConfigurationPropertiesCodeEmitter)
        registerEmitter(AsyncConfig::class, KotlinPoetAsyncCodeEmitter)
        registerEmitter(UntilSuccessfulConfig::class, KotlinPoetUntilSuccessfulCodeEmitter)
        registerEmitter(WithCorrelationIdConfig::class, KotlinPoetWithCorrelationIdCodeEmitter)
        registerEmitter(ChoiceConfig::class, KotlinPoetChoiceCodeEmitter)
        registerEmitter(ScatterGatherConfig::class, KotlinPoetScatterGatherCodeEmitter)
        registerEmitter(TryConfig::class, KotlinPoetTryCodeEmitter)
        registerEmitter(TransformConfig::class, KotlinPoetTransformCodeEmitter)
    }

    /**
     * Generates a complete Kotlin source file from a translation result using KotlinPoet.
     *
     * This is a stub implementation that will be completed in Wave 4.
     * Currently returns an empty string with a TODO marker.
     *
     * @param result The translation result containing flows, configs, and metadata
     * @param packageName The target package name for the generated class
     * @param nativeBuild If true, generates MuseConfiguration-based code (reflection-free);
     *                    if false, generates @MuseConfigComponent-based code
     * @return The complete Kotlin source code as a string
     */
    override fun generateFile(
        result: TranslationResult,
        packageName: String,
        nativeBuild: Boolean,
    ): String {
        // TODO: Full implementation in Wave 4
        // This stub demonstrates the KotlinPoet API structure:
        //
        // val className = classNameFrom(result.sourceFile.fileName.toString())
        //
        // val fileSpec = FileSpec.builder(packageName, className)
        //     .addImport("dev.museframework.springboot", "MuseConfigComponent")
        //     .addImport("dev.museframework.dsl.api.config", "MuseConfigDefinition")
        //     .addType(
        //         TypeSpec.classBuilder(className)
        //             .addAnnotation(ClassName("dev.museframework.springboot", "MuseConfigComponent"))
        //             .superclass(ClassName("dev.museframework.dsl.api.config", "MuseConfigDefinition"))
        //             .build()
        //     )
        //     .build()
        //
        // return fileSpec.toString()

        return ""
    }

    /**
     * Creates a new code generation context for managing imports and emission state.
     *
     * @param packageName The package name for the generated code
     * @return A new [CodegenContext] instance
     */
    override fun createContext(packageName: String): CodegenContext {
        return CodegenContext(packageName)
    }

    /**
     * Registers an emitter for a specific component type.
     *
     * The emitter type is determined by the component type it handles:
     * - [MuseConfiguration] → ConfigCodeEmitter
     * - [Source] → SourceCodeEmitter
     * - [PipelineElement] → ElementCodeEmitter
     *
     * @param type The Kotlin class of the component type this emitter handles
     * @param emitter The emitter instance (type will be checked at runtime)
     * @throws IllegalArgumentException if the emitter type doesn't match the component type
     */
    override fun registerEmitter(
        type: KClass<*>,
        emitter: Any,
    ) {
        when (emitter) {
            is ConfigCodeEmitter<*> -> configEmitters[type] = emitter
            is SourceCodeEmitter<*> -> sourceEmitters[type] = emitter
            is ElementCodeEmitter<*> -> elementEmitters[type] = emitter
            else -> throw IllegalArgumentException(
                "Emitter must be one of: ConfigCodeEmitter, SourceCodeEmitter, or ElementCodeEmitter"
            )
        }
    }

    /**
     * Derives a class name from a file name.
     * Converts "my-flow.xml" to "MyFlowConfig".
     */
    private fun classNameFrom(fileName: String): String {
        return fileName
            .removeSuffix(".xml")
            .split("-", "_")
            .joinToString("") { part ->
                part.lowercase().replaceFirstChar { it.uppercase() }
            }.plus("Config")
    }
}
