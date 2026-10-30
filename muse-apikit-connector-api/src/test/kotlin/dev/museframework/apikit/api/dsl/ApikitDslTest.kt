package dev.museframework.apikit.api.dsl

import dev.museframework.apikit.api.builder.ApikitBuilder
import dev.museframework.apikit.api.builder.ApikitConfigBuilder
import dev.museframework.apikit.api.config.ApikitConfig
import dev.museframework.apikit.api.config.ApikitRef
import dev.museframework.apikit.api.config.ApikitRouter
import dev.museframework.dsl.dsl.flow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ApikitDslTest {
    @Test
    fun `ApikitConfigBuilder should build ApikitConfig with defaults`() {
        val config = ApikitConfigBuilder()
            .apply { name = "test-api" }
            .build()

        assertNotNull(config.docId)
        assertEquals("", config.docName)
        assertNull(config.docDescription)
        assertNull(config.spec)
        assertFalse(config.disableValidations)
        assertEquals("test-api", config.name)
    }

    @Test
    fun `ApikitConfigBuilder should build ApikitConfig with custom docId and docName`() {
        val config =
            ApikitConfigBuilder()
                .apply {
                    name = "my-api"
                    docId = "custom-spec-id"
                    docName = "my-api-spec"
                }.build()

        assertEquals("custom-spec-id", config.docId)
        assertEquals("my-api-spec", config.docName)
        assertEquals("my-api", config.name)
    }

    @Test
    fun `ApikitConfigBuilder should build ApikitConfig with custom spec`() {
        val config =
            ApikitConfigBuilder()
                .apply {
                    name = "users-api"
                    spec = "api/users.raml"
                }.build()

        assertEquals("api/users.raml", config.spec)
    }

    @Test
    fun `ApikitConfigBuilder should build ApikitConfig with disableValidations`() {
        val config =
            ApikitConfigBuilder()
                .apply {
                    name = "test-api"
                    disableValidations = true
                }.build()

        assertTrue(config.disableValidations)
    }

    @Test
    fun `ApikitConfigBuilder should generate unique docId by default`() {
        val config1 = ApikitConfigBuilder().apply { name = "api1" }.build()
        val config2 = ApikitConfigBuilder().apply { name = "api2" }.build()

        assertNotEquals(config1.docId, config2.docId)
        assertNotNull(config1.docId)
        assertNotNull(config2.docId)
    }

    @Test
    fun `ApikitConfigBuilder should capture call site information`() {
        val config =
            ApikitConfigBuilder()
                .apply {
                    name = "test-api"
                    docName = "test-spec"
                }.build()

        assertNotNull(config.sourceFile)
        assertNotNull(config.sourceLine)
    }

    @Test
    fun `ApikitConfigBuilder should allow custom source metadata`() {
        val config =
            ApikitConfigBuilder()
                .apply {
                    name = "test-api"
                    docName = "test-spec"
                    sourceFile = "custom-file.xml"
                    sourceLine = 42
                    sourceClass = "CustomClass"
                }.build()

        assertEquals("custom-file.xml", config.sourceFile)
        assertEquals(42, config.sourceLine)
        assertEquals("CustomClass", config.sourceClass)
    }

    @Test
    fun `apikitConfig DSL should build ApikitConfig correctly`() {
        val config =
            apikitConfig {
                name = "my-api"
                docName = "my-api"
                docDescription = "My API specification"
            }

        assertEquals("my-api", config.name)
        assertEquals("my-api", config.docName)
        assertEquals("My API specification", config.docDescription)
        assertNotNull(config.docId)
    }

    @Test
    fun `apikitConfig DSL should build ApikitConfig with spec`() {
        val config =
            apikitConfig {
                name = "products-api"
                docName = "products-api"
                spec = "api/products.raml"
            }

        assertEquals("products-api", config.name)
        assertEquals("products-api", config.docName)
        assertEquals("api/products.raml", config.spec)
    }

    @Test
    fun `ApikitBuilder should build ApikitRouter with defaults`() {
        val config = ApikitBuilder()
            .apply { configRef = ApikitRef.ByName("test-ref") }
            .build()

        assertNotNull(config.docId)
        assertEquals("", config.docName)
        assertNull(config.docDescription)
        assertTrue(config.configRef is ApikitRef.ByName)
        assertEquals("test-ref", (config.configRef as ApikitRef.ByName).name)
    }

    @Test
    fun `ApikitBuilder should build ApikitRouter with custom docId and docName`() {
        val config =
            ApikitBuilder()
                .apply {
                    docId = "custom-apikit-id"
                    docName = "my-apikit-router"
                    configRef = ApikitRef.ByName("test-ref")
                }.build()

        assertEquals("custom-apikit-id", config.docId)
        assertEquals("my-apikit-router", config.docName)
    }

    @Test
    fun `ApikitBuilder should build ApikitRouter with ByReference configRef`() {
        val apiConfig = ApikitConfigBuilder()
            .apply { name = "my-api" }
            .build()

        val config =
            ApikitBuilder()
                .apply {
                    configRef = ApikitRef.ByReference(apiConfig)
                }.build()

        assertTrue(config.configRef is ApikitRef.ByReference)
        assertEquals(apiConfig, (config.configRef as ApikitRef.ByReference).config)
    }

    @Test
    fun `ApikitBuilder should generate unique docId by default`() {
        val config1 = ApikitBuilder()
            .apply { configRef = ApikitRef.ByName("ref1") }
            .build()
        val config2 = ApikitBuilder()
            .apply { configRef = ApikitRef.ByName("ref2") }
            .build()

        assertNotEquals(config1.docId, config2.docId)
        assertNotNull(config1.docId)
        assertNotNull(config2.docId)
    }

    @Test
    fun `ApikitBuilder should capture call site information`() {
        val config =
            ApikitBuilder()
                .apply {
                    configRef = ApikitRef.ByName("test-config")
                }.build()

        assertNotNull(config.sourceFile)
        assertNotNull(config.sourceLine)
    }

    @Test
    fun `ApikitBuilder should allow custom source metadata`() {
        val config =
            ApikitBuilder()
                .apply {
                    configRef = ApikitRef.ByName("test-config")
                    sourceFile = "flow-config.xml"
                    sourceLine = 100
                    sourceClass = "ApiFlow"
                }.build()

        assertEquals("flow-config.xml", config.sourceFile)
        assertEquals(100, config.sourceLine)
        assertEquals("ApiFlow", config.sourceClass)
    }

    @Test
    fun `ApikitBuilder should build ApikitRouter with all properties`() {
        val config =
            ApikitBuilder()
                .apply {
                    docId = "full-config-id"
                    docName = "Full APIKit Config"
                    docDescription = "Complete configuration test"
                    sourceFile = "complete.xml"
                    sourceLine = 50
                    sourceClass = "CompleteFlow"
                    configRef = ApikitRef.ByName("api-spec-ref")
                }.build()

        assertEquals("full-config-id", config.docId)
        assertEquals("Full APIKit Config", config.docName)
        assertEquals("Complete configuration test", config.docDescription)
        assertEquals("complete.xml", config.sourceFile)
        assertEquals(50, config.sourceLine)
        assertEquals("CompleteFlow", config.sourceClass)
        assertTrue(config.configRef is ApikitRef.ByName)
        assertEquals("api-spec-ref", (config.configRef as ApikitRef.ByName).name)
    }

    @Test
    fun `apikit DSL should add ApikitRouter to flow pipeline with String name`() {
        val testFlow =
            flow("test-flow") {
                apikit("my-api-spec") {}
            }

        assertEquals(1, testFlow.pipeline.size)
        val config = testFlow.pipeline[0] as ApikitRouter
        assertTrue(config.configRef is ApikitRef.ByName)
        assertEquals("my-api-spec", (config.configRef as ApikitRef.ByName).name)
    }

    @Test
    fun `apikit DSL should support custom docId and docName in flow with String name`() {
        val testFlow =
            flow("test-flow") {
                apikit("api-spec") {
                    docId = "custom-router-id"
                    docName = "API Router"
                }
            }

        val config = testFlow.pipeline[0] as ApikitRouter
        assertEquals("custom-router-id", config.docId)
        assertEquals("API Router", config.docName)
        assertTrue(config.configRef is ApikitRef.ByName)
        assertEquals("api-spec", (config.configRef as ApikitRef.ByName).name)
    }

    @Test
    fun `apikit DSL should generate unique docId in flow with String name`() {
        val testFlow =
            flow("test-flow") {
                apikit("spec1") {}
                apikit("spec2") {}
            }

        val config1 = testFlow.pipeline[0] as ApikitRouter
        val config2 = testFlow.pipeline[1] as ApikitRouter

        assertNotEquals(config1.docId, config2.docId)
        assertNotNull(config1.docId)
        assertNotNull(config2.docId)
    }

    @Test
    fun `apikit DSL should accept String name overload`() {
        val testFlow =
            flow("test-flow") {
                apikit("my-api") {
                    docName = "API Router"
                }
            }

        assertEquals(1, testFlow.pipeline.size)
        val config = testFlow.pipeline[0] as ApikitRouter
        assertTrue(config.configRef is ApikitRef.ByName)
        assertEquals("my-api", (config.configRef as ApikitRef.ByName).name)
        assertEquals("API Router", config.docName)
    }

    @Test
    fun `apikit DSL should accept ApikitConfig reference overload`() {
        val apiConfig = apikitConfig {
            name = "my-api"
            docName = "My API Config"
        }

        val testFlow =
            flow("test-flow") {
                apikit(apiConfig) {
                    docName = "API Router"
                }
            }

        assertEquals(1, testFlow.pipeline.size)
        val config = testFlow.pipeline[0] as ApikitRouter
        assertTrue(config.configRef is ApikitRef.ByReference)
        assertEquals(apiConfig, (config.configRef as ApikitRef.ByReference).config)
        assertEquals("my-api", config.configRef.config.name)
        assertEquals("API Router", config.docName)
    }

    @Test
    fun `apikit DSL with String name should support all builder properties`() {
        val testFlow =
            flow("test-flow") {
                apikit("named-api") {
                    docId = "named-router-id"
                    docName = "Named API Router"
                    docDescription = "Router using name reference"
                }
            }

        val config = testFlow.pipeline[0] as ApikitRouter
        assertEquals("named-router-id", config.docId)
        assertEquals("Named API Router", config.docName)
        assertEquals("Router using name reference", config.docDescription)
        assertTrue(config.configRef is ApikitRef.ByName)
        assertEquals("named-api", (config.configRef as ApikitRef.ByName).name)
    }

    @Test
    fun `apikit DSL with ApikitConfig should support all builder properties`() {
        val apiConfig = apikitConfig {
            name = "config-based-api"
            docName = "Config Based API"
            spec = "api/test.raml"
        }

        val testFlow =
            flow("test-flow") {
                apikit(apiConfig) {
                    docId = "config-router-id"
                    docName = "Config Router"
                    docDescription = "Router using config reference"
                }
            }

        val config = testFlow.pipeline[0] as ApikitRouter
        assertEquals("config-router-id", config.docId)
        assertEquals("Config Router", config.docName)
        assertEquals("Config Based API", (config.configRef as ApikitRef.ByReference).config.docName)
        assertEquals("config-based-api", config.configRef.config.name)
    }
}
