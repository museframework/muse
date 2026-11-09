package dev.museframework.apikit.parser

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
}
