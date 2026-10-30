package dev.museframework.babelfish.mappers.apikit

import dev.museframework.apikit.api.config.ApikitRef
import dev.museframework.apikit.api.config.ApikitRouter
import dev.museframework.babelfish.api.ElementMapper
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement

object ApikitRouterMapper : ElementMapper<ApikitRouter> {
    override fun map(
        element: RawElement,
        ctx: MappingContext,
    ): ApikitRouter {
        val configRef = element.requireAttr("config-ref", element.location)

        return ApikitRouter(
            configRef = ApikitRef.ByName(configRef),
            docName = element.attrs["doc:name"] ?: "",
            docId =
                element.attrs["doc:id"] ?: java.util.UUID
                    .randomUUID()
                    .toString(),
        )
    }
}
