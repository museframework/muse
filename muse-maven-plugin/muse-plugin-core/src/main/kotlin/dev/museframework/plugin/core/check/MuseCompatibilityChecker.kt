package dev.museframework.plugin.core.check

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.model.MuleDocument
import dev.museframework.babelfish.model.RawConfig
import dev.museframework.babelfish.model.RawElement
import dev.museframework.babelfish.model.RawFlow

/*
 * === Future Enhancements for muse:check ===
 *
 * 2. Config-ref Resolution Check:
 *    Verify every config-ref points to a defined global config.
 *    Requires cross-file resolution.
 *
 * 3. Flow Dependency Graph:
 *    Build a graph of flow-ref calls (flow -> subflow) and print it
 *    as an ASCII tree to help understand the app structure before migration.
 *
 * 4. Pattern Detection:
 *    - Detect choice routers with many routes (API Kit candidates)
 *    - Detect flow-ref cycles (migration risk)
 *    - Detect Mule 3 deprecated constructs in Mule 4 XML
 *    - Detect inline vs file-based DataWeave expressions
 *
 * 5. Variable Scope Trace:
 *    Track set-variable / remove-variable across flows and sub-flows
 *    to identify scope issues during translation.
 *
 * 6. Error Handler Coverage:
 *    Report flows without an error-handler (informational).
 *
 * 7. Property Placeholder Audit:
 *    Find ${...} placeholders in XML and verify they are defined
 *    in a configuration-properties file.
 */

class MuseCompatibilityChecker(
    private val registry: BabelfishRegistry,
) {
    private val structuralTags = setOf(
        "" to "when",
        "" to "otherwise",
        "" to "route",
        "" to "error-handler",
        "" to "on-error-continue",
        "" to "on-error-propagate",
        "" to "scheduling-strategy",
        "" to "fixed-frequency",
        "ee" to "message",
        "ee" to "set-payload",
    )

    private val knownUnimplemented = setOf(
        "apikit:console",
        "api-platform:policies",
        "api-platform:analytics",
    )


    fun check(documents: List<MuleDocument>): CompatibilityReport {
        val fileReports = mutableListOf<CheckReport>()
        val allNamespaces = mutableSetOf<String>()

        for (doc in documents) {
            val results = mutableListOf<CheckResult>()
            checkDocument(doc, results, allNamespaces)
            fileReports.add(CheckReport(doc.sourceFile, results))
        }

        return CompatibilityReport(fileReports, allNamespaces)
    }

    fun check(document: MuleDocument): CheckReport {
        val results = mutableListOf<CheckResult>()
        val namespaces = mutableSetOf<String>()
        checkDocument(document, results, namespaces)
        return CheckReport(document.sourceFile, results)
    }

    private fun checkDocument(
        doc: MuleDocument,
        results: MutableList<CheckResult>,
        namespaces: MutableSet<String>,
    ) {
        for (config in doc.globalConfigs) {
            checkConfig(config, results, namespaces)
        }
        for (flow in doc.flows) {
            checkFlow(flow, results, namespaces, isSubFlow = false)
        }
        for (subFlow in doc.subFlows) {
            checkFlow(subFlow, results, namespaces, isSubFlow = true)
        }
    }

    private fun checkFlow(
        flow: RawFlow,
        results: MutableList<CheckResult>,
        namespaces: MutableSet<String>,
        isSubFlow: Boolean,
    ) {
        val source = flow.source
        val hasSourceMapper = !isSubFlow && source != null && registry.hasSourceMapper(source)

        if (hasSourceMapper) {
            checkSource(source, results, namespaces)
        }

        val pipelineElements = if (hasSourceMapper) flow.pipeline else {
            if (source != null) listOf(source) + flow.pipeline else flow.pipeline
        }

        for (element in pipelineElements) {
            checkPipelineElement(element, 0, results, namespaces)
        }

        flow.errorHandler?.strategies?.forEach { strategy ->
            checkPipelineElement(strategy, 0, results, namespaces)
        }
    }

    private fun checkSource(
        element: RawElement,
        results: MutableList<CheckResult>,
        namespaces: MutableSet<String>,
    ) {
        namespaces.add(element.namespace)
        val implemented = registry.hasSourceMapper(element)
        results.add(
            CheckResult(
                namespace = element.namespace,
                tag = element.tag,
                type = ComponentType.SOURCE,
                implemented = implemented,
                location = element.location,
                depth = 0,
            )
        )
        element.children.forEach { checkPipelineElement(it, 1, results, namespaces) }
    }

    private fun checkConfig(
        config: RawConfig,
        results: MutableList<CheckResult>,
        namespaces: MutableSet<String>,
    ) {
        namespaces.add(config.namespace)

        val tagKey = if (config.namespace.isEmpty()) config.tag else "${config.namespace}:${config.tag}"
        if (tagKey in knownUnimplemented) return

        val implemented = registry.hasConfigMapper(config)
        results.add(
            CheckResult(
                namespace = config.namespace,
                tag = config.tag,
                type = ComponentType.CONFIG,
                implemented = implemented,
                location = config.location,
                depth = 0,
            )
        )
    }

    private fun checkPipelineElement(
        element: RawElement,
        depth: Int,
        results: MutableList<CheckResult>,
        namespaces: MutableSet<String>,
    ) {
        namespaces.add(element.namespace)

        val tagKey = if (element.namespace.isEmpty()) element.tag else "${element.namespace}:${element.tag}"

        when {
            isStructuralTag(element) -> {
                element.children.forEach { checkPipelineElement(it, depth, results, namespaces) }
            }
            tagKey in knownUnimplemented -> {
                element.children.forEach { checkPipelineElement(it, depth, results, namespaces) }
            }
            else -> {
                val implemented = registry.hasElementMapper(element)
                results.add(
                    CheckResult(
                        namespace = element.namespace,
                        tag = element.tag,
                        type = ComponentType.PROCESSOR,
                        implemented = implemented,
                        location = element.location,
                        depth = depth,
                    )
                )
                element.children.forEach { checkPipelineElement(it, depth + 1, results, namespaces) }
            }
        }
    }

    private fun isStructuralTag(element: RawElement): Boolean =
        element.namespace to element.tag in structuralTags
}
