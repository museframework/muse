package dev.museframework.babelfish.mappers.core

import dev.museframework.babelfish.api.ElementMapper
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.config.SetVariableTransformConfig
import dev.museframework.dsl.api.config.TransformConfig
import dev.museframework.dsl.api.expression.DwExpression
import java.util.UUID

object TransformMapper : ElementMapper<TransformConfig> {
    override fun map(
        element: RawElement,
        ctx: MappingContext,
    ): TransformConfig {
        val messageElement = element.children.find { it.tag == "message" }

        val setPayload =
            messageElement
                ?.children
                ?.find { it.tag == "set-payload" }
                ?.let(::mapExpression)

        val setAttributes =
            messageElement
                ?.children
                ?.find { it.tag == "set-attributes" }
                ?.let(::mapExpression)

        val variablesElement = element.children.find { it.tag == "variables" }

        val setVariables =
            variablesElement
                ?.children
                ?.filter { it.tag == "set-variable" }
                ?.map { varEl ->
                    val variableName = varEl.requireAttr("variableName", varEl.location)
                    SetVariableTransformConfig(
                        variableName = variableName,
                        expression = mapExpression(varEl),
                    )
                }
                ?: emptyList()

        return TransformConfig(
            docName = element.attrs["doc:name"] ?: "",
            docId = element.attrs["doc:id"] ?: UUID.randomUUID().toString(),
            setPayload = setPayload,
            setAttributes = setAttributes,
            setVariables = setVariables,
        )
    }

    private fun mapExpression(element: RawElement): DwExpression {
        val resource = element.attrs["resource"]
        val inline = element.textContent?.takeIf { it.isNotBlank() }

        if (resource != null) {
            require(resource.isNotBlank()) {
                "${element.tag} resource must not be blank at ${element.location}"
            }
            require(inline == null) {
                "${element.tag} must not define both resource and inline content at ${element.location}"
            }
            return DwExpression.Resource(resource)
        }

        require(inline != null) {
            "${element.tag} must define resource or inline content at ${element.location}"
        }
        return DwExpression.Inline(inline)
    }
}
