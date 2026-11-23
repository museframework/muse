package dev.museframework.babelfish.mappers.core

import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.babelfish.mapper.TranslationResult
import dev.museframework.dsl.api.model.component.PipelineElement
import dev.museframework.dsl.api.model.component.Source
import dev.museframework.dsl.api.model.flow.MuseFlow
import java.nio.file.Path

internal fun emitCore(element: PipelineElement): String =
    emitCore(MuseFlow.Flow("test", "flow-id", pipeline = listOf(element)))

internal fun emitCore(source: Source): String =
    emitCore(MuseFlow.Flow("test", "flow-id", source = source))

private fun emitCore(flow: MuseFlow.Flow): String =
    KotlinCodeEmitter()
        .apply { CoreMapperProvider().registerEmitters(this) }
        .emit(TranslationResult(Path.of("test.xml"), listOf(flow), emptyMap()), "com.example")
