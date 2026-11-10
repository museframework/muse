package dev.museframework.apikit.parser

import dev.museframework.apikit.ApiSpecificationParseException
import dev.museframework.apikit.api.model.ApiSpecification
import java.net.URLClassLoader
import java.nio.file.Files
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertFailsWith
import dev.museframework.http.api.model.HttpMethod
import java.net.URL
import java.nio.file.Paths
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AmfRamlParserTest {
    @Test
    fun `projects the existing migrated backend RAML without copying its fixture`() {
        val source = Paths.get(
            "..", "examples", "mule-test-backend", "src", "main", "resources", "api", "api-spec.raml",
        ).toAbsolutePath().normalize().toUri().toURL()
        val loader = object : ClassLoader(null) {
            override fun getResource(name: String): URL? =
                source.takeIf { name == "api/api-spec.raml" }
        }

        val spec = AmfRamlParser.parseRaml("api/api-spec.raml", loader)

        assertEquals(listOf("/api/resources"), spec.resources.map { it.path })
        val methods = spec.resources.single().methods
        assertEquals(setOf(HttpMethod.GET, HttpMethod.POST), methods.map { it.method }.toSet())
        assertTrue(methods.single { it.method == HttpMethod.GET }.contentTypes.isEmpty())
        assertEquals(
            listOf("application/octet-stream"),
            methods.single { it.method == HttpMethod.POST }.contentTypes,
        )
    }

    @Test
    fun `resolves classpath includes and request media type`() {
        val spec = AmfRamlParser.parseRaml("api/amf-includes.raml", javaClass.classLoader)
        val post = spec.resources.single { it.path == "/users" }.methods.single()

        assertEquals(HttpMethod.POST, post.method)
        assertEquals(listOf("application/json"), post.contentTypes)
    }
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `projects only request body when responses precede the method body`() {
        val spec = parse(
            """
            #%RAML 1.0
            title: Property Ordering
            /items:
              post:
                responses:
                  200:
                    body:
                      application/xml:
                        type: string
                body:
                  application/octet-stream:
                    type: string
            """,
        )

        assertEquals(listOf("application/octet-stream"), spec.resources.single().methods.single().contentTypes)
    }

    @Test
    fun `inherits root mediaType only for declared request body`() {
        val spec = parse(
            """
            #%RAML 1.0
            title: Root Default
            mediaType: application/json
            /items:
              get:
                responses:
                  200:
                    body:
                      application/xml:
                        type: string
              post:
                body:
                  type: string
            """,
        )

        val methods = spec.resources.single().methods
        assertTrue(methods.single { it.method == HttpMethod.GET }.contentTypes.isEmpty())
        assertEquals(listOf("application/json"), methods.single { it.method == HttpMethod.POST }.contentTypes)
    }

    @Test
    fun `inherits a list of root defaults for request body`() {
        val spec = parse(
            """
            #%RAML 1.0
            title: Multiple Defaults
            mediaType: [application/json, application/xml]
            /items:
              post:
                body:
                  type: string
            """,
        )

        assertEquals(
            setOf("application/json", "application/xml"),
            spec.resources.single().methods.single().contentTypes.toSet(),
        )
    }

    @Test
    fun `explicit request body type overrides root mediaType`() {
        val spec = parse(
            """
            #%RAML 1.0
            title: Override Default
            mediaType: application/json
            /items:
              post:
                body:
                  application/octet-stream:
                    type: string
            """,
        )

        assertEquals(listOf("application/octet-stream"), spec.resources.single().methods.single().contentTypes)
    }

    @Test
    fun `projects multiple explicit request media types`() {
        val spec = parse(
            """
            #%RAML 1.0
            title: Multiple Request Media Types
            /items:
              post:
                body:
                  application/json:
                    type: string
                  application/xml:
                    type: string
            """,
        )

        assertEquals(
            setOf("application/json", "application/xml"),
            spec.resources.single().methods.single().contentTypes.toSet(),
        )
    }
    @Test
    fun `rejects unsupported request media type rather than silently allowing any`() {
        assertFailsWith<ApiSpecificationParseException> {
            parse(
                """
                #%RAML 1.0
                title: Unsupported Media Constraint
                /items:
                  post:
                    body:
                      application/*:
                        type: string
                """,
            )
        }
    }

    private fun parse(source: String): ApiSpecification {
        val file = tempDir.resolve("api/spec.raml")
        Files.createDirectories(file.parent)
        Files.writeString(file, source.trimIndent())
        URLClassLoader(arrayOf(tempDir.toUri().toURL()), null).use { loader ->
            return AmfRamlParser.parseRaml("api/spec.raml", loader)
        }
    }
}
