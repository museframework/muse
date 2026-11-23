package dev.museframework.babelfish.parser

import dev.museframework.babelfish.exception.BabelfishParseException
import dev.museframework.babelfish.model.MuleDocument
import dev.museframework.babelfish.model.RawConfig
import dev.museframework.babelfish.model.RawElement
import dev.museframework.babelfish.model.RawErrorHandler
import dev.museframework.babelfish.model.RawFlow
import dev.museframework.babelfish.model.SourceLocation
import org.jdom2.Element
import org.jdom2.Namespace
import java.nio.file.Path

private const val DOCUMENTATION_NAMESPACE = "http://www.mulesoft.org/schema/mule/documentation"
private val documentationNamespace = Namespace.getNamespace("doc", DOCUMENTATION_NAMESPACE)

class MuleXmlParser {
    fun parse(file: Path): MuleDocument {
        val builder = LocationAwareSaxBuilder()
        val document =
            try {
                builder.build(file.toFile())
            } catch (e: Exception) {
                throw BabelfishParseException(file, e)
            }

        val root = document.rootElement
        val nsMap = buildNamespacePrefixMap(root)
        val locOf = { el: Element ->
            builder.locations[System.identityHashCode(el)] ?: SourceLocation(-1, -1)
        }

        val globalConfigs = mutableListOf<RawConfig>()
        val flows = mutableListOf<RawFlow>()
        val subFlows = mutableListOf<RawFlow>()
        val usedConfigIds =
            root.children
                .filterNot { it.name == "flow" || it.name == "sub-flow" }
                .mapNotNull(::explicitConfigId)
                .toMutableSet()
        var nextUnnamedConfig = 1

        for (child in root.children) {
            when (child.name) {
                "flow" -> {
                    flows.add(parseFlow(child, nsMap, locOf, isSubFlow = false))
                }

                "sub-flow" -> {
                    subFlows.add(parseFlow(child, nsMap, locOf, isSubFlow = true))
                }

                else -> {
                    val ns = nsMap[child.namespaceURI] ?: ""
                    val fallbackId =
                        if (explicitConfigId(child) == null) {
                            var candidate: String
                            do {
                                candidate = "unnamed_config_${nextUnnamedConfig++}"
                            } while (!usedConfigIds.add(candidate))
                            candidate
                        } else {
                            null
                        }
                    globalConfigs.add(parseConfig(child, ns, nsMap, locOf, fallbackId))
                }
            }
        }

        return MuleDocument(file, globalConfigs, flows, subFlows)
    }

    private fun parseFlow(
        el: Element,
        nsMap: Map<String, String>,
        locOf: (Element) -> SourceLocation,
        isSubFlow: Boolean,
    ): RawFlow {
        val loc = locOf(el)
        val name =
            el.getAttributeValue("name")
                ?: throw MissingFlowNameException(loc)

        val rawChildren = el.children.map { toRaw(it, nsMap, locOf) }

        val errorHandler =
            rawChildren
                .find { it.tag == "error-handler" }
                ?.let { RawErrorHandler(it.children) }

        val nonError = rawChildren.filter { it.tag != "error-handler" }

        val source = if (!isSubFlow) nonError.firstOrNull() else null
        val pipeline = if (source != null) nonError.drop(1) else nonError

        return RawFlow(
            name = name,
            source = source,
            pipeline = pipeline,
            errorHandler = errorHandler,
            location = loc,
            docId = el.attributes.firstOrNull { it.namespaceURI == DOCUMENTATION_NAMESPACE && it.name == "id" }?.value,
        )
    }

    private fun toRaw(
        el: Element,
        nsMap: Map<String, String>,
        locOf: (Element) -> SourceLocation,
    ): RawElement {
        val attrMap = buildMap {
            for (attribute in el.attributes) {
                when {
                    attribute.namespaceURI == DOCUMENTATION_NAMESPACE -> put("doc:${attribute.name}", attribute.value)
                    attribute.namespacePrefix != "doc" -> put(attribute.qualifiedName, attribute.value)
                }
            }
        }
        return RawElement(
            namespace = nsMap[el.namespaceURI] ?: "",
            tag = el.name,
            attrs = attrMap,
            children = el.children.map { toRaw(it, nsMap, locOf) },
            location = locOf(el),
            textContent = el.textTrim.takeIf { it.isNotEmpty() },
            untrimmedTextContent = if (el.children.isEmpty()) el.text.takeIf(String::isNotEmpty) else null,
        )
    }

    private fun parseConfig(
        el: Element,
        ns: String,
        nsMap: Map<String, String>,
        locOf: (Element) -> SourceLocation,
        fallbackId: String?,
    ): RawConfig {
        return RawConfig(
            id = explicitConfigId(el) ?: requireNotNull(fallbackId),
            namespace = ns,
            tag = el.name,
            attrs =
                buildMap {
                    el.attributes.forEach { attr ->
                        when {
                            attr.namespaceURI == DOCUMENTATION_NAMESPACE -> {
                                put(attr.qualifiedName, attr.value)
                                if (attr.name == "id") put("doc:id", attr.value)
                            }

                            // A literal `doc` prefix without this namespace is not documentation metadata.
                            attr.namespacePrefix != "doc" -> put(attr.qualifiedName, attr.value)
                        }
                    }
                },
            children = el.children.map { toRaw(it, nsMap, locOf) },
            location = locOf(el),
            textContent = el.textTrim.takeIf { it.isNotEmpty() },
        )
    }

    private fun explicitConfigId(el: Element): String? =
        sequenceOf(
            el.getAttributeValue("name"),
            el.getAttributeValue("name", documentationNamespace),
            el.getAttributeValue("id", documentationNamespace),
        ).filterNotNull().firstOrNull { it.isNotBlank() }

    private fun buildNamespacePrefixMap(root: Element): Map<String, String> = root.namespacesInScope.associate { ns -> ns.uri to ns.prefix }
}

class MissingFlowNameException(
    loc: SourceLocation,
) : Exception("Found <flow> without 'name' attribute at $loc")
