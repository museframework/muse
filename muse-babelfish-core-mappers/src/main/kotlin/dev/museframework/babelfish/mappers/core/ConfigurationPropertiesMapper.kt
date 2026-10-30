package dev.museframework.babelfish.mappers.core

import dev.museframework.babelfish.api.ConfigMapper
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawConfig
import dev.museframework.dsl.api.config.ConfigurationPropertiesConfig
import dev.museframework.dsl.api.model.config.MuseConfiguration

/**
 * Maps `configuration-properties` XML elements to [ConfigurationPropertiesConfig].
 *
 * XML Structure:
 * ```xml
 * <configuration-properties doc:name="Configuration properties"
 *                         doc:id="..."
 *                         file="properties/properties.yaml" />
 * ```
 */
object ConfigurationPropertiesMapper : ConfigMapper {
    override fun map(
        config: RawConfig,
        ctx: MappingContext,
    ): MuseConfiguration {
        val file =
            config.attrs["file"]
                ?: throw IllegalArgumentException(
                    "configuration-properties requires 'file' attribute at ${config.location}",
                )

        return ConfigurationPropertiesConfig(
            file = file,
            docName = config.attrs["doc:name"] ?: "",
            docId = config.attrs["doc:id"] ?: "",
        )
    }
}
