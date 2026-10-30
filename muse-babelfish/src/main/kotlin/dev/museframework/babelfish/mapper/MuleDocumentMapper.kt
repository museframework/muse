package dev.museframework.babelfish.mapper

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.api.ElementMapper
import dev.museframework.babelfish.api.SourceMapper
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.babelfish.exception.BabelfishTranslationException
import dev.museframework.babelfish.exception.UnmappedElementException
import dev.museframework.babelfish.model.MuleDocument
import dev.museframework.babelfish.model.RawElement
import dev.museframework.babelfish.model.RawFlow
import dev.museframework.dsl.api.model.component.PipelineElement
import dev.museframework.dsl.api.model.component.Source
import dev.museframework.dsl.api.model.error.ErrorHandler
import dev.museframework.dsl.api.model.flow.MuseFlow
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.writeText

data class TranslationResult(
    val sourceFile: Path,
    val flows: List<MuseFlow>,
    val usedConfigs: Map<String, dev.museframework.dsl.api.model.config.MuseConfiguration>,
) {
    fun writeTo(
        outputDir: Path,
        packageName: String,
    ): List<Path> {
        val emitter = KotlinCodeEmitter()
        val code = emitter.emit(this, packageName)
        val outputFile = outputDir.resolve(sourceFile.fileName.toString().replace(".xml", ".kt"))
        Files.createDirectories(outputFile.parent)
        outputFile.writeText(code)
        return listOf(outputFile)
    }
}

class MuleDocumentMapper(
    private val registry: BabelfishRegistry,
) {
    // Elements that are acceptable to skip in lenient mode
    private val lenientSkipElements =
        setOf(
            "apikit:console",
            "api-platform:policies",
            "api-platform:analytics",
            "configuration-properties",
        )

    fun map(
        doc: MuleDocument,
        lenient: Boolean = false,
    ): TranslationResult {
        val ctx = MappingContext(registry, doc.sourceFile)
        val unmappedErrors = mutableListOf<UnmappedElementException>()

        doc.globalConfigs.forEach { config ->
            try {
                val mapper = registry.resolveConfig(config, doc.sourceFile)
                ctx.configs[config.id] = mapper.map(config, ctx)
            } catch (e: UnmappedElementException) {
                unmappedErrors.add(e)
            } catch (e: Exception) {
                // resolved via ByName at runtime
            }
        }

        // Pass 2: Map flows and sub-flows
        val flows =
            doc.flows.mapNotNull { rawFlow ->
                try {
                    mapFlow(rawFlow, ctx, unmappedErrors, isSubFlow = false)
                } catch (e: UnmappedElementException) {
                    unmappedErrors.add(e)
                    null
                }
            }
        val subFlows =
            doc.subFlows.mapNotNull { rawFlow ->
                try {
                    mapFlow(rawFlow, ctx, unmappedErrors, isSubFlow = true)
                } catch (e: UnmappedElementException) {
                    unmappedErrors.add(e)
                    null
                }
            }

        if (unmappedErrors.isNotEmpty()) {
            // In lenient mode, filter out acceptable skipped elements
            val filteredErrors =
                if (lenient) {
                    unmappedErrors.filter { error ->
                        val elementKey = if (error.namespace.isEmpty()) error.tag else "${error.namespace}:${error.tag}"
                        !lenientSkipElements.contains(elementKey)
                    }
                } else {
                    unmappedErrors
                }

            if (filteredErrors.isNotEmpty()) {
                throw BabelfishTranslationException(filteredErrors)
            }
        }

        return TranslationResult(
            sourceFile = doc.sourceFile,
            flows = flows + subFlows,
            usedConfigs = ctx.configs.toMap(),
        )
    }

    private fun mapFlow(
        raw: RawFlow,
        ctx: MappingContext,
        errors: MutableList<UnmappedElementException>,
        isSubFlow: Boolean,
    ): MuseFlow {
        val docId = raw.source?.attrs?.get("doc:id") ?: UUID.randomUUID().toString()

        // Map error handler if present
        val errorHandler =
            raw.errorHandler?.let { rawEh ->
                try {
                    ErrorHandlerMapper.map(
                        RawElement(
                            namespace = "",
                            tag = "error-handler",
                            attrs = emptyMap(),
                            children = rawEh.strategies,
                            location = raw.location,
                        ),
                        ctx,
                    )
                } catch (e: UnmappedElementException) {
                    errors.add(e)
                    null
                }
            }

        return if (isSubFlow) {
            val pipeline =
                raw.pipeline.mapNotNull { el ->
                    try {
                        @Suppress("UNCHECKED_CAST")
                        (registry.resolveElement(el, ctx.sourceFile) as ElementMapper<PipelineElement>)
                            .map(el, ctx)
                    } catch (e: UnmappedElementException) {
                        errors.add(e)
                        null
                    }
                }

            MuseFlow.SubFlow(
                name = raw.name,
                docId = docId,
                pipeline = pipeline,
            )
        } else {
            val sourceEl = raw.source
            val hasSourceMapper = sourceEl != null && registry.hasSourceMapper(sourceEl)

            val allElements = if (sourceEl != null) listOf(sourceEl) + raw.pipeline else raw.pipeline

            val pipelineElements = if (hasSourceMapper) raw.pipeline else allElements

            val pipeline =
                pipelineElements.mapNotNull { el ->
                    try {
                        @Suppress("UNCHECKED_CAST")
                        (registry.resolveElement(el, ctx.sourceFile) as ElementMapper<PipelineElement>)
                            .map(el, ctx)
                    } catch (e: UnmappedElementException) {
                        errors.add(e)
                        null
                    }
                }

            val source =
                if (hasSourceMapper && sourceEl != null) {
                    try {
                        @Suppress("UNCHECKED_CAST")
                        (registry.resolveSource(sourceEl, ctx.sourceFile) as SourceMapper<Source>)
                            .map(sourceEl, ctx)
                    } catch (e: UnmappedElementException) {
                        errors.add(e)
                        null
                    }
                } else {
                    null
                }

            MuseFlow.Flow(
                name = raw.name,
                docId = docId,
                source = source,
                pipeline = pipeline,
                errorHandler = errorHandler,
            )
        }
    }
}
