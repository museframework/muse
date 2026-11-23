package dev.museframework.e2e.packaged

import dev.museframework.apikit.api.config.ApikitRef
import dev.museframework.apikit.api.config.ApikitRouter
import dev.museframework.dsl.api.config.ChoiceConfig
import dev.museframework.dsl.api.config.MuseConfigDefinition
import dev.museframework.dsl.api.config.RemoveVariableConfig
import dev.museframework.dsl.api.config.SchedulerConfig
import dev.museframework.dsl.api.config.SetPayloadConfig
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.config.TransformConfig
import dev.museframework.dsl.api.config.WithCorrelationIdConfig
import dev.museframework.dsl.api.expression.DwExpression
import dev.museframework.dsl.api.model.config.ConfigRef
import dev.museframework.e2e.packaged.generated.ApikitWireConfig
import dev.museframework.e2e.packaged.generated.ComponentIdentityConfig
import dev.museframework.e2e.packaged.generated.ConnectionsConfig
import dev.museframework.e2e.packaged.generated.CoreFlowControlConfig
import dev.museframework.e2e.packaged.generated.CrossFileSubflowConfig
import dev.museframework.e2e.packaged.generated.OutboundRequestConfig
import dev.museframework.e2e.packaged.generated.SimpleEchoConfig
import dev.museframework.http.api.config.ConnectionConfigRef
import dev.museframework.http.api.config.HttpListenerConfig
import dev.museframework.http.api.config.HttpRequestConfig
import dev.museframework.http.api.config.HttpStreamingMode
import dev.museframework.http.api.model.HttpMethod
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GeneratedKotlinCompilationIT {
    @Test
    fun `generated configuration and application classes load from compiled bytecode`() {
        val generatedConfigClasses =
            listOf(
                "ApikitWireConfig",
                "ChoiceErrorConfig",
                "ComponentIdentityConfig",
                "ConnectionsConfig",
                "CoreFlowControlConfig",
                "CrossFileEntryConfig",
                "CrossFileSubflowConfig",
                "OutboundRequestConfig",
                "SimpleEchoConfig",
            ).map(::loadGeneratedClass)

        generatedConfigClasses.forEach { generatedClass ->
            assertTrue(
                MuseConfigDefinition::class.java.isAssignableFrom(generatedClass),
                "${generatedClass.name} must extend MuseConfigDefinition",
            )
            assertNotNull(generatedClass.getDeclaredConstructor().newInstance())
        }

        val applicationClass = loadGeneratedClass("MusePackagedE2eApplicationKt")
        val mainMethod = applicationClass.getDeclaredMethod("main", Array<String>::class.java)

        assertTrue(Modifier.isPublic(mainMethod.modifiers))
        assertTrue(Modifier.isStatic(mainMethod.modifiers))
        assertEquals(Void.TYPE, mainMethod.returnType)
    }

    @Test
    fun `compiled APIKit router binds to the original config name and classpath RAML`() {
        val generated = ApikitWireConfig()
        val config = generated.wireApi
        val router = assertIs<ApikitRouter>(generated.packagedApikitWire.pipeline.single())

        assertEquals("wire-api", config.name)
        assertEquals("api/apikit-\$wire.raml", config.spec)
        assertEquals("Wire \"API\"\r\nroutes", config.docDescription)
        assertFalse(config.disableValidations)
        assertEquals(config.name, assertIs<ApikitRef.ByName>(router.configRef).name)
    }

    @Test
    fun `compiled Kotlin keeps distinct flow and payload XML IDs including escaped characters`() {
        val flow = SimpleEchoConfig().packagedEchoFlow

        assertEquals("echo-flow-original", flow.docId)
        assertEquals("echo-\"quoted\"-\$", assertIs<SetPayloadConfig>(flow.pipeline.single()).docId)
    }

    @Test
    fun `compiled Kotlin keeps router and same-type branch processor XML IDs`() {
        val flow = CoreFlowControlConfig().coreChoiceFirstMatch
        val choice = assertIs<ChoiceConfig>(flow.pipeline.single())

        assertEquals("choice-flow-original", flow.docId)
        assertEquals("choice-router-original", choice.docId)
        assertEquals(
            listOf("choice-first-original", "choice-second-original"),
            choice.whenRoutes.map { route -> assertIs<SetPayloadConfig>(route.pipeline.single()).docId },
        )
    }

    @Test
    fun `compiled Kotlin keeps subflow and nested payload XML IDs`() {
        val subFlow = CrossFileSubflowConfig().packagedCrossFileSubflow

        assertEquals("cross-file-subflow-original", subFlow.docId)
        assertEquals("cross-file-payload-original", assertIs<SetPayloadConfig>(subFlow.pipeline.single()).docId)
    }

    @Test
    fun `compiled Kotlin keeps the scheduler router and nested processor IDs of a single flow`() {
        val flow = ComponentIdentityConfig().scheduledIdentity
        val scheduler = assertIs<SchedulerConfig>(flow.source)
        val correlation = assertIs<WithCorrelationIdConfig>(flow.pipeline.single())
        val payload = assertIs<SetPayloadConfig>(correlation.pipeline[0])
        val remove = assertIs<RemoveVariableConfig>(correlation.pipeline[1])

        assertEquals("flow\nidentity", flow.docId)
        assertEquals("scheduler\r\nidentity", scheduler.docId)
        assertEquals("correlation-identity", correlation.docId)
        assertEquals("identity-correlation", correlation.correlationId)
        assertEquals("payload\r\nidentity", payload.docId)
        assertEquals("remove-identity", remove.docId)
        assertEquals("temporary", remove.variableName)
    }

    @Test
    fun `compiled Kotlin preserves exact payload values`() {
        val values = ComponentIdentityConfig().literalValues.pipeline.map { assertIs<SetPayloadConfig>(it).value }

        assertEquals(
            listOf(
                "",
                "first\r\nlast\r\n",
                "\n|margin\n",
                "first\rlast",
                "tab\there",
                "\"quote\" \"\"\"",
                "back\\slash \$ \${literal} 100% \uD83D\uDE42",
            ),
            values,
        )
    }

    @Test
    fun `compiled Kotlin preserves a multiline DataWeave expression with nested interpolation`() {
        val transform = assertIs<TransformConfig>(ComponentIdentityConfig().transformLiterals.pipeline.single())
        val payload = assertIs<DwExpression.Inline>(transform.setPayload)

        val expected = "%dw 2.0\r\noutput application/json\n---\n" +
            """{ labels: ["$(vars.outer ++ "$(vars.inner)")", "quote: \"ok\""] }"""
        assertEquals(expected, payload.expression)
    }

    @Test
    fun `compiled Kotlin resolves the HTTP request extension alongside its namesake flow property`() {
        val generated = OutboundRequestConfig()
        val request = assertIs<HttpRequestConfig>(generated.httpRequest.pipeline.single())

        assertEquals("/e2e/backend", request.path)
        assertEquals(HttpMethod.PATCH, request.method)
        assertEquals(HttpStreamingMode.NEVER, request.requestStreamingMode)
        assertEquals(TargetConfig("httpReply", "#[{payload: payload, attributes: attributes}]"), request.targetConfig)
        val dollar = '$'
        assertEquals(
            """#[vars.headers default {"header-\"-${dollar}-\\": "value-\"-${dollar}-\\"}]""",
            request.headersExpression,
        )
        assertEquals(
            """#[vars.query default {"query-\"-${dollar}-\\": "value-\"-${dollar}-\\"}]""",
            request.queryParamsExpression,
        )
    }

    @Test
    fun `compiled HTTP request connection preserves exact default names and values`() {
        val generated = OutboundRequestConfig()
        val connection = generated.httpBindingFixture
        val request = assertIs<HttpRequestConfig>(generated.httpRequest.pipeline.single())
        val requestRef = assertIs<ConfigRef.ByName>(request.connectionConfig)

        assertEquals("http-binding-fixture", requestRef.name)
        assertEquals(connection.name, requestRef.name)
        val value = "value-\"-\$-\\\r\nnext"
        assertEquals(mapOf("header-\"-\$-\\" to value), connection.defaultHeaders)
        assertEquals(mapOf("query-\"-\$-\\" to value), connection.defaultQueryParams)
    }

    @Test
    fun `compiled listener and request references match connection names across files`() {
        val connections = ConnectionsConfig()
        val flow = OutboundRequestConfig().packagedOutboundProxyFlow
        val listener = assertIs<HttpListenerConfig>(flow.source)
        val request = assertIs<HttpRequestConfig>(flow.pipeline.single())

        val listenerRef = assertIs<ConnectionConfigRef.ByName>(listener.connectionConfigRef)
        val requestRef = assertIs<ConfigRef.ByName>(request.connectionConfig)

        assertEquals("packaged-listener", listenerRef.name)
        assertEquals(connections.packagedListener.name, listenerRef.name)
        assertEquals("Packaged listener", connections.packagedListener.docName)
        assertEquals(listOf(HttpMethod.POST), listener.allowedMethods)
        assertEquals("packaged-loopback", requestRef.name)
        assertEquals(connections.packagedLoopback.name, requestRef.name)
        assertEquals("Packaged loopback request", connections.packagedLoopback.docName)
    }

    private fun loadGeneratedClass(simpleName: String): Class<*> =
        Class.forName("$GENERATED_PACKAGE.$simpleName")

    private companion object {
        const val GENERATED_PACKAGE = "dev.museframework.e2e.packaged.generated"
    }
}
