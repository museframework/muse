package dev.museframework.babelfish.api

import dev.museframework.babelfish.exception.UnmappedElementException
import dev.museframework.babelfish.model.RawConfig
import dev.museframework.babelfish.model.RawElement
import dev.museframework.babelfish.spi.BabelfishRegistrationOwner
import dev.museframework.babelfish.spi.OwnedRegistration
import dev.museframework.babelfish.spi.registerIfAbsent
import dev.museframework.dsl.api.model.component.PipelineElement
import dev.museframework.dsl.api.model.component.Source
import java.nio.file.Path

class BabelfishRegistry {

    private val elementMappers = mutableMapOf<NamespaceTag, OwnedRegistration<ElementMapper<*>>>()
    private val sourceMappers = mutableMapOf<NamespaceTag, OwnedRegistration<SourceMapper<*>>>()
    private val configMappers = mutableMapOf<NamespaceTag, OwnedRegistration<ConfigMapper>>()
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

    fun registerElement(namespace: String, tag: String, mapper: ElementMapper<*>) {
        val key = NamespaceTag(namespace, tag)
        elementMappers.registerIfAbsent(key, key.displayName, "element mapper", mapper, registrationOwner)
    }

    fun registerSource(namespace: String, tag: String, mapper: SourceMapper<*>) {
        val key = NamespaceTag(namespace, tag)
        sourceMappers.registerIfAbsent(key, key.displayName, "source mapper", mapper, registrationOwner)
    }

    fun registerConfig(namespace: String, tag: String, mapper: ConfigMapper) {
        val key = NamespaceTag(namespace, tag)
        configMappers.registerIfAbsent(key, key.displayName, "configuration mapper", mapper, registrationOwner)
    }

    fun resolveElement(el: RawElement, file: Path): ElementMapper<*> =
        elementMappers[NamespaceTag(el.namespace, el.tag)]?.value
            ?: throw UnmappedElementException(el.namespace, el.tag, el.location, file)

    fun resolveSource(el: RawElement, file: Path): SourceMapper<*> =
        sourceMappers[NamespaceTag(el.namespace, el.tag)]?.value
            ?: throw UnmappedElementException(el.namespace, el.tag, el.location, file)

    fun resolveConfig(config: RawConfig, file: Path): ConfigMapper =
        configMappers[NamespaceTag(config.namespace, config.tag)]?.value
            ?: throw UnmappedElementException(config.namespace, config.tag, config.location, file)

    fun hasSourceMapper(el: RawElement): Boolean =
        sourceMappers.containsKey(NamespaceTag(el.namespace, el.tag))

    fun hasElementMapper(el: RawElement): Boolean =
        elementMappers.containsKey(NamespaceTag(el.namespace, el.tag))

    fun hasConfigMapper(config: RawConfig): Boolean =
        configMappers.containsKey(NamespaceTag(config.namespace, config.tag))

    private data class NamespaceTag(
        val namespace: String,
        val tag: String,
    ) {
        val displayName: String = if (namespace.isEmpty()) tag else "$namespace:$tag"
    }
}
