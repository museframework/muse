package dev.museframework.babelfish.parser

import dev.museframework.babelfish.exception.BabelfishParseException
import dev.museframework.babelfish.model.MuleDocument
import dev.museframework.babelfish.model.RawConfig
import dev.museframework.babelfish.model.RawElement
import dev.museframework.babelfish.model.RawErrorHandler
import dev.museframework.babelfish.model.RawFlow
import dev.museframework.babelfish.model.SourceLocation
import org.jdom2.Element
import java.nio.file.Path

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

        return RawFlow(name, source, pipeline, errorHandler, loc)
    }

    private fun toRaw(
        el: Element,
        nsMap: Map<String, String>,
        locOf: (Element) -> SourceLocation,
    ): RawElement {
        val attrMap = el.attributes.associate { attr -> attr.qualifiedName to attr.value }
        return RawElement(
            namespace = nsMap[el.namespaceURI] ?: "",
            tag = el.name,
            attrs = attrMap,
            children = el.children.map { toRaw(it, nsMap, locOf) },
            location = locOf(el),
            textContent = el.textTrim.takeIf { it.isNotEmpty() },
        )
    }

    private fun parseConfig(
        el: Element,
        ns: String,
        nsMap: Map<String, String>,
        locOf: (Element) -> SourceLocation,
        fallbackId: String?,
    ): RawConfig {
        // Extract doc:name and doc:id from attributes (handle namespaced attrs)
        var docName: String? = null
        var docId: String? = null
        for (attr in el.attributes) {
            if (attr.namespacePrefix == "doc") {
                when (attr.name) {
                    "name" -> docName = attr.value
                    "id" -> docId = attr.value
                }
            }
        }

        return RawConfig(
            id = explicitConfigId(el) ?: requireNotNull(fallbackId),
            namespace = ns,
            tag = el.name,
            attrs =
                el.attributes.associate { attr ->
                    attr.qualifiedName to attr.value
                },
            children = el.children.map { toRaw(it, nsMap, locOf) },
            location = locOf(el),
            textContent = el.textTrim.takeIf { it.isNotEmpty() },
        )
    }

    private fun explicitConfigId(el: Element): String? =
        sequenceOf(
            el.getAttributeValue("name"),
            el.attributes.firstOrNull { it.namespacePrefix == "doc" && it.name == "name" }?.value,
            el.attributes.firstOrNull { it.namespacePrefix == "doc" && it.name == "id" }?.value,
        ).filterNotNull().firstOrNull { it.isNotBlank() }

    private fun buildNamespacePrefixMap(root: Element): Map<String, String> = root.namespacesInScope.associate { ns -> ns.uri to ns.prefix }
}

class MissingFlowNameException(
    loc: SourceLocation,
) : Exception("Found <flow> without 'name' attribute at $loc")
