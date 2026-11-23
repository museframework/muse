package dev.museframework.babelfish.emitter

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.NameAllocator
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.buildCodeBlock
import com.squareup.kotlinpoet.joinToCode
import dev.museframework.babelfish.mapper.ErrorHandlerStrategyCodeEmitter
import dev.museframework.babelfish.mapper.TranslationResult
import dev.museframework.dsl.api.config.MuseConfigDefinition
import dev.museframework.dsl.api.model.config.MuseConfiguration
import dev.museframework.babelfish.spi.BabelfishRegistrationOwner
import dev.museframework.babelfish.spi.OwnedRegistration
import dev.museframework.babelfish.spi.registerIfAbsent
import dev.museframework.dsl.api.model.component.PipelineElement
import dev.museframework.dsl.api.model.component.Source
import dev.museframework.dsl.api.model.flow.MuseFlow
import java.nio.file.Path
import kotlin.reflect.KClass

class KotlinCodeEmitter {
    private val configEmitters = mutableMapOf<KClass<*>, OwnedRegistration<ConfigCodeEmitter<*>>>()
    private val sourceEmitters = mutableMapOf<KClass<*>, OwnedRegistration<SourceCodeEmitter<*>>>()
    private val elementEmitters = mutableMapOf<KClass<*>, OwnedRegistration<ElementCodeEmitter<*>>>()
    private var registrationOwner = BabelfishRegistrationOwner.DIRECT

    fun <T> withRegistrationOwner(
        owner: BabelfishRegistrationOwner,
        registration: () -> T,
    ): T {
        val previousOwner = registrationOwner
        registrationOwner = owner
        return try {
            registration()
        } finally {
            registrationOwner = previousOwner
        }
    }

    fun <T : MuseConfiguration> registerConfigEmitter(
        type: KClass<T>,
        emitter: ConfigCodeEmitter<T>,
    ) {
        configEmitters.registerIfAbsent(
            type,
            type.java.name,
            "configuration emitter",
            emitter,
            registrationOwner,
        )
    }

    fun <T : Source> registerSourceEmitter(
        type: KClass<T>,
        emitter: SourceCodeEmitter<T>,
    ) {
        sourceEmitters.registerIfAbsent(type, type.java.name, "source emitter", emitter, registrationOwner)
    }

    fun <T : PipelineElement> registerElementEmitter(
        type: KClass<T>,
        emitter: ElementCodeEmitter<T>,
    ) {
        elementEmitters.registerIfAbsent(type, type.java.name, "element emitter", emitter, registrationOwner)
    }

    fun emit(
        result: TranslationResult,
        packageName: String,
        nativeBuild: Boolean = false,
        configurationClassSpec: ConfigurationClassSpec = ConfigurationClassSpec.Plain,
    ): String = emitFile(result, packageName, nativeBuild, configurationClassSpec).toString()

    fun emitFile(
        result: TranslationResult,
        packageName: String,
        nativeBuild: Boolean = false,
        configurationClassSpec: ConfigurationClassSpec = ConfigurationClassSpec.Plain,
    ): FileSpec {
        val declaration = configurationClassSpec.declaration(nativeBuild)
        val className = classNameFrom(result.sourceFile)
        val type = TypeSpec.classBuilder(className)
        declaration.annotationTypes.forEach { type.addAnnotation(ClassName.bestGuess(it)) }
        val ctx = CodegenContext(packageName, result.sourceFile, elementEmitters.entries.associate { (type, entry) ->
            requireNotNull(type.qualifiedName) to entry.value
        })
        if (declaration.isProvider) {
            type.addSuperinterface(ClassName.bestGuess(requireNotNull(declaration.providerInterface)))
            ctx.reserveProviderNames()
        } else {
            type.superclass(MuseConfigDefinition::class)
        }

        // Preallocate all declarations so forward references use their final names.
        val configNames = result.usedConfigs.keys.map(ctx::configValName)
        val flowNames = result.flows.map { ctx.flowValName(it.name) }
        result.usedConfigs.forEach { (id, config) ->
            @Suppress("UNCHECKED_CAST")
            val emitter = configEmitters[config::class]?.value as? ConfigCodeEmitter<MuseConfiguration>
                ?: ctx.missingEmitter("configuration", config)
            type.addProperty(
                PropertySpec.builder(ctx.configValName(id), config::class)
                    .initializer(emitter.emit(config, ctx))
                    .build(),
            )
        }
        for (flow in result.flows) {
            ctx.currentFlow = flow.name
            type.addProperty(
                PropertySpec.builder(ctx.flowValName(flow.name), flow::class)
                    .initializer(emitFlow(flow, ctx))
                    .build(),
            )
        }
        if (declaration.isProvider) {
            if (flowNames.isNotEmpty()) type.addFunction(providerList("flows", MuseFlow::class, flowNames))
            if (configNames.isNotEmpty()) type.addFunction(providerList("configs", MuseConfiguration::class, configNames))
        }
        return FileSpec.builder(packageName, className)
            .addKotlinDefaultImports(includeJs = false)
            .addType(type.build())
            .build()
    }

    private fun emitFlow(flow: MuseFlow, ctx: CodegenContext): CodeBlock {
        val function = when (flow) {
            is MuseFlow.Flow -> MemberName("dev.museframework.dsl.dsl", "flow")
            is MuseFlow.SubFlow -> MemberName("dev.museframework.dsl.dsl", "subFlow")
        }
        val mainFlow = flow as? MuseFlow.Flow
        return buildCodeBlock {
            beginControlFlow("%M(%L)", function, kotlinStringLiteral(flow.name))
            addStatement("docId = %L", kotlinStringLiteral(flow.docId))
            mainFlow?.source?.let { source ->
                @Suppress("UNCHECKED_CAST")
                val emitter = sourceEmitters[source::class]?.value as? SourceCodeEmitter<Source>
                    ?: ctx.missingEmitter("source", source)
                add("%L\n", emitter.emit(source, ctx))
            }
            add(ctx.emitPipelineElements(flow.pipeline))
            mainFlow?.errorHandler?.let { handler ->
                beginControlFlow("errorHandler")
                handler.strategies.forEach { add(ErrorHandlerStrategyCodeEmitter.emitStrategy(it, ctx)) }
                endControlFlow()
            }
            endControlFlow()
        }
    }

    private fun providerList(name: String, itemType: KClass<*>, properties: List<String>): FunSpec =
        FunSpec.builder(name)
            .addModifiers(KModifier.OVERRIDE)
            .returns(LIST.parameterizedBy(itemType.asClassName()))
            .addStatement(
                "return %M(%L)",
                MemberName("kotlin.collections", "listOf"),
                properties.map { CodeBlock.of("%N", it) }.joinToCode(),
            )
            .build()

    private fun classNameFrom(file: Path): String {
        val stem = file.fileName.toString().substringBeforeLast('.')
        val name = stem.split(IDENTIFIER_SEPARATORS).joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        return NameAllocator().newName(name + "Config")
    }
}
