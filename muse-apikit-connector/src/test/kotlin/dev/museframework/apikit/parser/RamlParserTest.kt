package dev.museframework.apikit.parser

import dev.museframework.apikit.ApiSpecificationNotFoundException
import dev.museframework.apikit.ApiSpecificationParseException
import dev.museframework.apikit.ApiSpecificationReadException
import dev.museframework.http.api.model.HttpMethod
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RamlParserTest {
    @Test
    fun `loads and parses the specification exactly once`() {
        val openCount = AtomicInteger()
        val classLoader =
            resourceClassLoader(
                "api/valid.raml",
                """
                #%RAML 1.0
                title: Test API
                /users:
                  get:
                  post:
                """.trimIndent(),
                openCount,
            )

        val specification = RamlParser.parseRaml("/api/valid.raml", classLoader)

        assertEquals(1, openCount.get())
        assertEquals(listOf("/users"), specification.resources.map { it.path })
        assertEquals(
            listOf(HttpMethod.GET, HttpMethod.POST),
            specification.resources.single().methods.map { it.method },
        )
    }

    @Test
    fun `missing resource produces a typed error with its path`() {
        val failure =
            assertFailsWith<ApiSpecificationNotFoundException> {
                RamlParser.parseRaml("api/missing.raml", ClassLoader.getPlatformClassLoader())
            }

        assertEquals("api/missing.raml", failure.specPath)
        assertTrue(failure.message.orEmpty().contains("api/missing.raml"))
    }

    @Test
    fun `read failure preserves the original cause`() {
        val original = IOException("simulated read failure")
        val classLoader = resourceClassLoader("api/unreadable.raml") { throw original }

        val failure =
            assertFailsWith<ApiSpecificationReadException> {
                RamlParser.parseRaml("api/unreadable.raml", classLoader)
            }

        assertSame(original, failure.cause)
        assertEquals("api/unreadable.raml", failure.specPath)
    }

    @Test
    fun `missing RAML header is a parse error`() {
        val classLoader = resourceClassLoader("api/malformed.raml", "title: Missing header")

        val failure =
            assertFailsWith<ApiSpecificationParseException> {
                RamlParser.parseRaml("api/malformed.raml", classLoader)
            }

        assertTrue(failure.message.orEmpty().contains("missing #%RAML 1.0 header"))
    }

    @Test
    fun `malformed resource declaration is a parse error`() {
        val classLoader =
            resourceClassLoader(
                "api/malformed-resource.raml",
                """
                #%RAML 1.0
                /users
                  get:
                """.trimIndent(),
            )

        val failure =
            assertFailsWith<ApiSpecificationParseException> {
                RamlParser.parseRaml("api/malformed-resource.raml", classLoader)
            }

        assertTrue(failure.message.orEmpty().contains("line 2"))
    }

    private fun resourceClassLoader(
        resourceName: String,
        content: String,
        openCount: AtomicInteger = AtomicInteger(),
    ): ClassLoader =
        resourceClassLoader(resourceName) {
            openCount.incrementAndGet()
            ByteArrayInputStream(content.toByteArray())
        }

    private fun resourceClassLoader(
        resourceName: String,
        inputStream: () -> InputStream,
    ): ClassLoader {
        val resource =
            URL.of(
                URI.create("memory:$resourceName"),
                object : URLStreamHandler() {
                    override fun openConnection(url: URL): URLConnection =
                        object : URLConnection(url) {
                            override fun connect() = Unit

                            override fun getInputStream(): InputStream = inputStream()
                        }
                },
            )
        return object : ClassLoader(null) {
            override fun getResource(name: String): URL? = resource.takeIf { name == resourceName }
        }
    }
}
