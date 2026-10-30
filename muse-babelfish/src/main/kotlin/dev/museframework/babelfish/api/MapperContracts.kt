package dev.museframework.babelfish.api

import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawConfig
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.model.component.PipelineElement
import dev.museframework.dsl.api.model.component.Source
import dev.museframework.dsl.api.model.config.MuseConfiguration

fun interface ElementMapper<out T : PipelineElement> {
    fun map(element: RawElement, ctx: MappingContext): T
}

fun interface SourceMapper<out T : Source> {
    fun map(element: RawElement, ctx: MappingContext): T
}

fun interface ConfigMapper {
    fun map(config: RawConfig, ctx: MappingContext): MuseConfiguration
}
