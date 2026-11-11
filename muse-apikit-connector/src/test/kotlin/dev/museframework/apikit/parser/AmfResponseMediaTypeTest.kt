package dev.museframework.apikit.parser

import dev.museframework.apikit.ApiSpecificationParseException
import dev.museframework.http.api.model.HttpMethod
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AmfResponseMediaTypeTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `projects only successful response bodies and never substitutes request Content-Type`() {
        val spec = parse(
            """
            #%RAML 1.0
            title: APIKit Accept fixture
            /items:
              post:
                responses:
                  200:
                    body:
                      application/json:
                        type: string
                  201:
                    body:
                      application/xml:
                        type: string
                  400:
                    body:
                      text/plain:
                        type: string
                body:
                  application/octet-stream:
                    type: string
            """,
        )
        val post = spec.resources.single().methods.single()
        assertEquals(HttpMethod.POST, post.method)
        assertEquals(listOf("application/octet-stream"), post.contentTypes)
        assertEquals(setOf("application/json", "application/xml"), post.responseMediaTypes.toSet())
    }

    @Test
    fun `inherits root default for a successful response body and ignores a bodyless 204`() {
        val spec = parse(
            """
            #%RAML 1.0
            title: Inherited Media Type
            mediaType: application/json
            /items:
              get:
                responses:
                  200:
                    body:
                      type: string
                  204:
                    description: No body
            """,
        )
        val get = spec.resources.single().methods.single()
        assertEquals(listOf("application/json"), get.responseMediaTypes)
        assertEquals(emptyList(), get.contentTypes)
    }

    @Test
    fun `no successful response body does not produce an Accept constraint`() {
        val spec = parse(
            """
            #%RAML 1.0
            title: No Representation
            /items:
              get:
                responses:
                  204:
                    description: No body
                  400:
                    body:
                      application/json:
                        type: string
            """,
        )
        assertEquals(emptyList(), spec.resources.single().methods.single().responseMediaTypes)
    }

    @Test
    fun `unprojectable success response media type fails bootstrap rather than disabling 406`() {
        assertFailsWith<ApiSpecificationParseException> {
            parse(
                """
                #%RAML 1.0
                title: Unsupported Success Type
                /items:
                  get:
                    responses:
                      200:
                        body:
                          application/*:
                            type: string
                """,
            )
        }
    }

    private fun parse(source: String): dev.museframework.apikit.api.model.ApiSpecification {
        val file = tempDir.resolve("api/spec.raml")
        Files.createDirectories(file.parent)
        Files.writeString(file, source.trimIndent())
        URLClassLoader(arrayOf(tempDir.toUri().toURL()), null).use { loader ->
            return AmfRamlParser.parseRaml("api/spec.raml", loader)
        }
    }
}
