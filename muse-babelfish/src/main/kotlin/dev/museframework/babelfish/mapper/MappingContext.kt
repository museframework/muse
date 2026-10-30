package dev.museframework.babelfish.mapper

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.api.ElementMapper
import dev.museframework.babelfish.exception.UnmappedElementException
import dev.museframework.dsl.api.model.component.PipelineElement
import dev.museframework.dsl.api.model.config.MuseConfiguration
import java.nio.file.Path

class MappingContext(
    val registry: BabelfishRegistry,
    val sourceFile: Path
) {
    val configs: MutableMap<String, MuseConfiguration> = mutableMapOf()

    fun resolveConfig(ref: String): MuseConfiguration? {
        val config = configs[ref]
        if (config == null) {
            // Don't fail - config-ref might be in a different XML file.
            // Return a placeholder - the config-ref will be resolved at runtime or in a separate translation pass.
            return null
        }
        return config
    }

    fun tryResolveConfig(ref: String): MuseConfiguration? = configs[ref]

    /**
     * Recursively maps a RawElement to a PipelineElement using the registry.
     * This is the key method that router mappers use to map their child elements.
     *
     * @throws UnmappedElementException if no mapper is registered for the element
     */
    @Suppress("UNCHECKED_CAST")
    fun mapPipelineElement(element: dev.museframework.babelfish.model.RawElement): PipelineElement {
        val mapper = registry.resolveElement(element, sourceFile) as ElementMapper<PipelineElement>
        return mapper.map(element, this)
    }

    /**
     * Maps a list of RawElements to PipelineElements, collecting any unmapped errors.
     * Returns only successfully mapped elements.
     */
    fun mapPipelineElements(
        elements: List<dev.museframework.babelfish.model.RawElement>,
        errors: MutableList<UnmappedElementException> = mutableListOf()
    ): List<PipelineElement> {
        return elements.mapNotNull { el ->
            try {
                mapPipelineElement(el)
            } catch (e: UnmappedElementException) {
                errors.add(e)
                null
            }
        }
    }
}
