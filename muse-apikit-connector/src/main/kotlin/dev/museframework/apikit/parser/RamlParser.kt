package dev.museframework.apikit.parser

import dev.museframework.apikit.ApiSpecificationNotFoundException
import dev.museframework.apikit.ApiSpecificationParseException
import dev.museframework.apikit.ApiSpecificationReadException
import dev.museframework.apikit.api.model.ApiMethod
import dev.museframework.apikit.api.model.ApiResource
import dev.museframework.apikit.api.model.ApiSpecification
import dev.museframework.http.api.model.HttpMethod

/**
 * Parses RAML 1.0 specification files and extracts API resources and methods.
 * Uses simple string parsing (no external dependencies).
 * Promoted from spring adapter to core for reuse by Spring and Quarkus.
 */
object RamlParser {

    /**
     * Parses a RAML 1.0 spec file from the classpath.
     *
     * @param specPath Path to the RAML file (e.g., "api/api-spec.raml")
     * @param classLoader ClassLoader to load the resource
     * @return ApiSpecification with parsed resources
     * @throws dev.museframework.apikit.ApiSpecificationBootstrapException when the resource cannot be loaded or parsed
     */
    fun parseRaml(specPath: String, classLoader: ClassLoader): ApiSpecification {
        val normalizedPath = specPath.removePrefix("/")
        val resource =
            classLoader.getResource(normalizedPath)
                ?: throw ApiSpecificationNotFoundException(specPath)
        val content = loadRamlContent(specPath, resource)
        return parseRamlContent(specPath, content)
    }

    private fun loadRamlContent(
        specPath: String,
        resource: java.net.URL,
    ): String =
        try {
            resource.openStream().bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            throw ApiSpecificationReadException(specPath, e)
        }

    private fun parseRamlContent(
        specPath: String,
        content: String,
    ): ApiSpecification {
        val lines = content.lines()
        val header = lines.firstOrNull()?.removePrefix("\uFEFF")?.trim()

        if (header?.startsWith("#%RAML 1.0") != true) {
            throw ApiSpecificationParseException(specPath, "missing #%RAML 1.0 header")
        }

        val resources = mutableListOf<ApiResource>()
        val context = ParsingContext()

        for ((index, line) in lines.withIndex()) {
            parseLine(specPath, index + 1, line, context, resources)
        }

        return ApiSpecification(resources = resources)
    }

    private fun parseLine(
        specPath: String,
        lineNumber: Int,
        line: String,
        context: ParsingContext,
        resources: MutableList<ApiResource>,
    ) {
        val trimmed = line.trimEnd()
        if (trimmed.isBlank() || trimmed.startsWith("#")) {
            return
        }

        val indent = countIndentation(line)
        val content = trimmed.trimStart()

        if (content.startsWith("/") && !content.startsWith("/ ")) {
            if (!content.substringBefore("#").trimEnd().endsWith(":")) {
                throw ApiSpecificationParseException(
                    specPath,
                    "resource declaration at line $lineNumber must end with ':'",
                )
            }
            val resourcePath = extractResourcePath(content, context, indent)
            context.currentResourcePath = resourcePath
            context.currentResourceIndent = indent
            context.currentMethods = mutableListOf()
            return
        }

        val httpMethod = parseHttpMethod(content)
        if (httpMethod != null && context.currentResourcePath != null) {
            if (!content.substringBefore("#").trimEnd().endsWith(":")) {
                throw ApiSpecificationParseException(
                    specPath,
                    "HTTP method declaration at line $lineNumber must end with ':'",
                )
            }
            if (indent > context.currentResourceIndent) {
                context.currentMethods.add(httpMethod)
                updateOrAddResource(resources, context)
            }
        }
    }

    private fun countIndentation(line: String): Int {
        var count = 0
        for (char in line) {
            if (char == ' ') count++
            else if (char == '\t') count += 2
            else break
        }
        return count
    }

    private fun extractResourcePath(content: String, context: ParsingContext, indent: Int): String {
        val path = content.takeWhile { it != ':' && it != ' ' }

        return if (indent == 0) {
            path
        } else {
            val parentPath = findParentPath(context, indent)
            if (parentPath != null) {
                "$parentPath$path"
            } else {
                path
            }
        }
    }

    private fun findParentPath(context: ParsingContext, currentIndent: Int): String? {
        return if (currentIndent > context.currentResourceIndent && context.currentResourcePath != null) {
            context.currentResourcePath
        } else {
            null
        }
    }

    private fun parseHttpMethod(content: String): HttpMethod? {
        val methodName = content.takeWhile { it != ':' && it != ' ' }.lowercase()
        return when (methodName) {
            "get" -> HttpMethod.GET
            "post" -> HttpMethod.POST
            "put" -> HttpMethod.PUT
            "delete" -> HttpMethod.DELETE
            "patch" -> HttpMethod.PATCH
            "head" -> HttpMethod.HEAD
            "options" -> HttpMethod.OPTIONS
            else -> null
        }
    }

    private fun updateOrAddResource(resources: MutableList<ApiResource>, context: ParsingContext) {
        val path = context.currentResourcePath ?: return

        val existingIndex = resources.indexOfFirst { it.path == path }
        val apiMethods = context.currentMethods.map { method ->
            ApiMethod(method = method)
        }

        if (existingIndex >= 0) {
            resources[existingIndex] = ApiResource(
                path = path,
                methods = apiMethods
            )
        } else {
            resources.add(ApiResource(
                path = path,
                methods = apiMethods
            ))
        }
    }

    private data class ParsingContext(
        var currentResourcePath: String? = null,
        var currentResourceIndent: Int = -1,
        var currentMethods: MutableList<HttpMethod> = mutableListOf()
    )
}
