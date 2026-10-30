package dev.museframework.babelfish.emitter

import dev.museframework.babelfish.mapper.TranslationResult
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

    fun <T : dev.museframework.dsl.api.model.config.MuseConfiguration> registerConfigEmitter(
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
    ): String {
        val ctx =
            CodegenContext(packageName).apply {
                elementEmitters =
                    this@KotlinCodeEmitter
                        .elementEmitters
                        .map { (type, registration) -> type.qualifiedName!! to registration.value }
                        .toMap()
            }
        val className = classNameFrom(result.sourceFile.fileName.toString())
        val declaration = configurationClassSpec.declaration(nativeBuild)

        declaration.annotationTypes.forEach(ctx::addImport)
        ctx.addImport("dev.museframework.dsl.dsl.flow")
        ctx.addImport("dev.museframework.dsl.dsl.subFlow")
        if (declaration.isProvider) {
            ctx.addImport("dev.museframework.dsl.api.model.config.MuseConfiguration")
            ctx.addImport("dev.museframework.dsl.api.model.flow.MuseFlow")
            ctx.addImport(requireNotNull(declaration.providerInterface))
        } else {
            ctx.addImport("dev.museframework.dsl.api.config.MuseConfigDefinition")
        }

        val configVals = emitConfigs(result, ctx)
        val flowVals = emitFlows(result, ctx)

        return buildString {
            appendLine("package $packageName")
            appendLine()
            ctx.resolvedImports().forEach { appendLine("import $it") }
            appendLine()
            declaration.annotationTypes.forEach { appendLine("@${it.substringAfterLast('.')}") }
            if (declaration.isProvider) {
                appendLine("class $className : ${requireNotNull(declaration.providerInterface).substringAfterLast('.')} {")
            } else {
                appendLine("class $className : MuseConfigDefinition() {")
            }
            appendLine()

            if (configVals.isNotBlank()) {
                append(configVals.prependIndent("    "))
                appendLine()
            }
            if (flowVals.isNotBlank()) {
                append(flowVals.prependIndent("    "))
                appendLine()
            }

            if (declaration.isProvider) {
                if (result.flows.isNotEmpty()) {
                    appendLine("    override fun flows(): List<MuseFlow> =")
                    appendLine("        listOf(")
                    result.flows.forEachIndexed { index, flow ->
                        val comma = if (index < result.flows.lastIndex) "," else ""
                        appendLine("            ${ctx.flowValName(flow.name)}$comma")
                    }
                    appendLine("        )")
                    appendLine()
                }
                if (result.usedConfigs.isNotEmpty()) {
                    appendLine("    override fun configs(): List<MuseConfiguration> =")
                    appendLine("        listOf(")
                    result.usedConfigs.keys.forEachIndexed { index, key ->
                        val comma = if (index < result.usedConfigs.size - 1) "," else ""
                        appendLine("            ${ctx.configValName(key)}$comma")
                    }
                    appendLine("        )")
                    appendLine()
                }
            }

            appendLine("}")
        }
    }

    private fun emitConfigs(
        result: TranslationResult,
        ctx: CodegenContext,
    ): String =
        buildString {
            result.usedConfigs.forEach { (muleId, config) ->
                val valName = ctx.configValName(muleId)
                val emitter = configEmitters[config::class]?.value
                if (emitter != null) {
                    @Suppress("UNCHECKED_CAST")
                    val code = (emitter as ConfigCodeEmitter<Any>).emit(config, ctx)
                    appendLine("val $valName = $code")
                }
            }
        }

    private fun emitFlows(
        result: TranslationResult,
        ctx: CodegenContext,
    ): String =
        buildString {
            result.flows.forEach { flow ->
                val valName = ctx.flowValName(flow.name)
                appendLine("val $valName = ${emitFlow(flow, ctx)}")
                appendLine()
            }
        }

    private fun emitFlow(
        flow: MuseFlow,
        ctx: CodegenContext,
    ): String =
        when (flow) {
            is MuseFlow.Flow -> {
                buildString {
                    appendLine("flow(\"${ctx.escapeKotlinString(flow.name)}\") {")
                    flow.source?.let { source ->
                        val emitter = sourceEmitters[source::class]?.value
                        if (emitter != null) {
                            @Suppress("UNCHECKED_CAST")
                            val code = (emitter as SourceCodeEmitter<Any>).emit(source, ctx)
                            appendLine(" $code")
                        }
                    }
                    flow.pipeline.forEach { el ->
                        val emitter = elementEmitters[el::class]?.value
                        if (emitter != null) {
                            @Suppress("UNCHECKED_CAST")
                            val code = (emitter as ElementCodeEmitter<Any>).emit(el, ctx)
                            appendLine(code.prependIndent(" "))
                        }
                    }
                    flow.errorHandler?.let { eh ->
                        appendLine(" errorHandler {")
                        eh.strategies.forEach { strategy ->
                            append(
                                dev.museframework.babelfish.mapper.ErrorHandlerStrategyCodeEmitter
                                    .emitStrategy(strategy, ctx)
                                    .prependIndent("     "),
                            )
                            appendLine()
                        }
                        appendLine(" }")
                    }
                    append("}")
                }
            }

            is MuseFlow.SubFlow -> {
                buildString {
                    appendLine("subFlow(\"${ctx.escapeKotlinString(flow.name)}\") {")
                    flow.pipeline.forEach { el ->
                        val emitter = elementEmitters[el::class]?.value
                        if (emitter != null) {
                            @Suppress("UNCHECKED_CAST")
                            val code = (emitter as ElementCodeEmitter<Any>).emit(el, ctx)
                            appendLine(code.prependIndent(" "))
                        }
                    }
                    append("}")
                }
            }
        }

    private fun classNameFrom(fileName: String): String =
        fileName
            .removeSuffix(".xml")
            .split("-", "_")
            .joinToString("") { part ->
                part.lowercase().replaceFirstChar { it.uppercase() }
            }.plus("Config")
}
