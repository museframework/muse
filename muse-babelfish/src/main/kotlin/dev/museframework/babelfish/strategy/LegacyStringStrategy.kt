package dev.museframework.babelfish.strategy

import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ConfigCodeEmitter
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.babelfish.emitter.SourceCodeEmitter
import dev.museframework.babelfish.mapper.TranslationResult
import dev.museframework.dsl.api.model.component.PipelineElement
import dev.museframework.dsl.api.model.component.Source
import dev.museframework.dsl.api.model.config.MuseConfiguration
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf

/**
 * Legacy string-based code generation strategy that wraps [KotlinCodeEmitter].
 *
 * This strategy provides a bridge between the new [CodeGenerationStrategy] interface
 * and the existing [KotlinCodeEmitter] implementation, allowing gradual migration
 * to alternative strategies (e.g., KotlinPoet-based) while maintaining backward compatibility.
 *
 * @param emitter The underlying [KotlinCodeEmitter] instance to delegate to
 */
class LegacyStringStrategy(
    private val emitter: KotlinCodeEmitter = KotlinCodeEmitter(),
) : CodeGenerationStrategy {

    override fun generateFile(
        result: TranslationResult,
        packageName: String,
        nativeBuild: Boolean,
    ): String = emitter.emit(result, packageName, nativeBuild)

    override fun createContext(packageName: String): CodegenContext =
        CodegenContext(packageName)

    override fun registerEmitter(
        type: KClass<*>,
        emitter: Any,
    ) {
        when {
            type.isSubclassOf(MuseConfiguration::class) -> {
                @Suppress("UNCHECKED_CAST")
                this.emitter.registerConfigEmitter(
                    type as KClass<MuseConfiguration>,
                    emitter as ConfigCodeEmitter<MuseConfiguration>,
                )
            }

            type.isSubclassOf(Source::class) -> {
                @Suppress("UNCHECKED_CAST")
                this.emitter.registerSourceEmitter(
                    type as KClass<Source>,
                    emitter as SourceCodeEmitter<Source>,
                )
            }

            type.isSubclassOf(PipelineElement::class) -> {
                @Suppress("UNCHECKED_CAST")
                this.emitter.registerElementEmitter(
                    type as KClass<PipelineElement>,
                    emitter as ElementCodeEmitter<PipelineElement>,
                )
            }

            else -> throw IllegalArgumentException(
                "Unsupported emitter type: ${type.qualifiedName}. " +
                    "Emitter must be for MuseConfiguration, Source, or PipelineElement.",
            )
        }
    }
}
