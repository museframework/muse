package dev.museframework.apikit.parser

import amf.apicontract.client.platform.RAMLConfiguration
import amf.apicontract.client.platform.model.domain.api.WebApi
import amf.core.client.common.remote.Content
import amf.core.client.common.transform.PipelineId
import amf.core.client.platform.model.document.Document
import amf.core.client.platform.resource.ResourceLoader
import dev.museframework.apikit.ApiSpecificationBootstrapException
import dev.museframework.apikit.ApiSpecificationNotFoundException
import dev.museframework.apikit.ApiSpecificationParseException
import dev.museframework.apikit.ApiSpecificationReadException
import dev.museframework.apikit.api.model.ApiMethod
import dev.museframework.apikit.api.model.ApiParameter
import dev.museframework.apikit.api.model.ApiResource
import dev.museframework.apikit.api.model.ApiSpecification
import dev.museframework.http.api.model.HttpMethod
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Projects an AMF-resolved RAML 1.0 document into Muse's framework-neutral API model.
 * This adapter deliberately does not perform HTTP media type validation or choose a Mule flow.
 */
internal object AmfRamlParser {
    fun parseRaml(specPath: String, classLoader: ClassLoader): ApiSpecification {
        val path = specPath.removePrefix("/")
        if (path.isBlank() || path.split('/').any { it == ".." || it == "." || it.isEmpty() }) {
            throw ApiSpecificationParseException(specPath, "invalid classpath specification path")
        }
        val url = classLoader.getResource(path) ?: throw ApiSpecificationNotFoundException(specPath)
        val content = try {
            url.openStream().use(::readBounded)
        } catch (e: IOException) {
            throw ApiSpecificationReadException(specPath, e)
        }
        if (!content.lineSequence().firstOrNull().orEmpty().removePrefix("\uFEFF").trim().startsWith("#%RAML 1.0")) {
            throw ApiSpecificationParseException(specPath, "missing #%RAML 1.0 header")
        }

        return try {
            val loader = ClasspathRamlResourceLoader(path, content, classLoader)
            val client = RAMLConfiguration.RAML10().withResourceLoader(loader).baseUnitClient()
            val parsed = client.parse(loader.rootUri).get()
            val source = parsed.baseUnit()
                ?: throw ApiSpecificationParseException(specPath, "AMF did not produce a document")
            val resolved = client.transform(source, PipelineId.Editing()).baseUnit() as? Document
                ?: throw ApiSpecificationParseException(specPath, "AMF did not resolve a RAML document")
            val report = client.validate(resolved).get()
            if (!report.conforms()) {
                val details = report.results().take(5).joinToString("; ") { issue ->
                    "${issue.message()} (line ${issue.position().start().line()})"
                }
                throw ApiSpecificationParseException(specPath, details.ifBlank { "AMF validation failed" })
            }
            val api = resolved.encodes() as? WebApi
                ?: throw ApiSpecificationParseException(specPath, "AMF document is not a Web API")
            ApiSpecification(
                resources = api.endPoints().mapNotNull { endpoint ->
                    val methods = endpoint.operations().map { operation ->
                        val request = operation.request()
                        ApiMethod(
                            method = HttpMethod.valueOf(operation.method().value().uppercase()),
                            contentTypes = request?.payloads()
                                ?.mapNotNull { payload ->
                                    payload.mediaType().takeIf { it.nonNull() }?.value()
                                }
                                ?.distinct()
                                .orEmpty(),
                            requiredHeaders = request?.headers()
                                ?.filter { it.required().nonNull() && it.required().value() }
                                ?.map { ApiParameter(it.parameterName().value()) }
                                .orEmpty(),
                            requiredQueryParams = request?.queryParameters()
                                ?.filter { it.required().nonNull() && it.required().value() }
                                ?.map { ApiParameter(it.parameterName().value()) }
                                .orEmpty(),
                        )
                    }
                    if (methods.isEmpty()) null else ApiResource(path = endpoint.path().value(), methods = methods)
                },
            )
        } catch (e: ApiSpecificationBootstrapException) {
            throw e
        } catch (e: Exception) {
            val cause = if (e is ExecutionException) e.cause ?: e else e
            throw ApiSpecificationParseException(specPath, cause.message ?: cause.javaClass.simpleName, cause)
        }
    }
}

private class ClasspathRamlResourceLoader(
    private val rootPath: String,
    private val rootContent: String,
    private val classLoader: ClassLoader,
) : ResourceLoader {
    val rootUri: String = URI("file", null, "$ROOT_PREFIX$rootPath", null).toString()
    private val reads = AtomicInteger()

    override fun fetch(resourceName: String): CompletableFuture<Content> {
        val result = CompletableFuture<Content>()
        try {
            val uri = URI(resourceName).normalize()
            require(uri.scheme == "file" && uri.host == null && uri.query == null && uri.fragment == null) {
                "RAML references must remain within the application classpath"
            }
            val absolutePath = uri.path
            require(absolutePath.startsWith(ROOT_PREFIX)) { "RAML reference escapes the classpath root" }
            val path = absolutePath.removePrefix(ROOT_PREFIX)
            require(path.isNotBlank() && path.split('/').none { it == ".." || it == "." || it.isEmpty() }) {
                "Invalid RAML resource path"
            }
            require(reads.incrementAndGet() <= MAX_REFERENCES) { "RAML reference limit exceeded" }
            val body = if (path == rootPath) rootContent else {
                val url = classLoader.getResource(path)
                    ?: error("RAML reference not found on the classpath: $path")
                url.openStream().use(::readBounded)
            }
            result.complete(Content(body, resourceName))
        } catch (e: Exception) {
            result.completeExceptionally(e)
        }
        return result
    }

    private companion object {
        const val ROOT_PREFIX = "/__muse_raml__/"
        const val MAX_REFERENCES = 64
    }
}

private fun readBounded(stream: java.io.InputStream): String {
    val bytes = stream.readNBytes(2 * 1024 * 1024 + 1)
    if (bytes.size > 2 * 1024 * 1024) throw IOException("RAML resource exceeds 2 MiB limit")
    return String(bytes, StandardCharsets.UTF_8)
}
