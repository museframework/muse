package dev.museframework.babelfish.mappers.apikit

import dev.museframework.apikit.api.config.ApikitConfig
import dev.museframework.babelfish.api.ConfigMapper
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawConfig
import java.util.UUID

/**
 * Maps `apikit:config` XML elements to [ApikitConfig].
 *
 * XML Structure:
 * ```xml
 * <apikit:config name="apiConfig"
 *                raml="api/interface.raml"
 *                outboundHeadersMapName="outboundHeaders"
 *                httpStatusVarName="httpStatus"
 *                doc:name="APIKit Config"
 *                doc:id="abc-123"/>
 * ```
 */
object ApikitConfigMapper : ConfigMapper {
    override fun map(
        config: RawConfig,
        ctx: MappingContext,
    ): ApikitConfig {
        val name =
            config.attrs["name"]
                ?: throw IllegalArgumentException(
                    "apikit:config requires 'name' attribute at ${config.location}",
                )

        val specPath =
            config.attrs["raml"] ?: config.attrs["api"]
                ?: throw IllegalArgumentException(
                    "apikit:config requires 'raml' or 'api' attribute at ${config.location}",
                )

        val docName = config.attrs["doc:name"] ?: name
        val docId = config.attrs["doc:id"] ?: UUID.randomUUID().toString()
        val docDescription = config.attrs["doc:description"]

        return ApikitConfig(
            name = name,
            docName = docName,
            docId = docId,
            docDescription = docDescription,
            sourceFile = ctx.sourceFile.toString(),
            spec = specPath,
            disableValidations = false,
        )
    }
}
