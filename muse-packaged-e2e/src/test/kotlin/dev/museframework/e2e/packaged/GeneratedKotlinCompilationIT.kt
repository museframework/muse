package dev.museframework.e2e.packaged

import dev.museframework.dsl.api.config.ChoiceConfig
import dev.museframework.dsl.api.config.MuseConfigDefinition
import dev.museframework.dsl.api.config.RemoveVariableConfig
import dev.museframework.dsl.api.config.SchedulerConfig
import dev.museframework.dsl.api.config.SetPayloadConfig
import dev.museframework.dsl.api.config.WithCorrelationIdConfig
import dev.museframework.e2e.packaged.generated.ComponentIdentityConfig
import dev.museframework.e2e.packaged.generated.CoreFlowControlConfig
import dev.museframework.e2e.packaged.generated.CrossFileSubflowConfig
import dev.museframework.e2e.packaged.generated.SimpleEchoConfig
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
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

    private fun loadGeneratedClass(simpleName: String): Class<*> =
        Class.forName("$GENERATED_PACKAGE.$simpleName")

    private companion object {
        const val GENERATED_PACKAGE = "dev.museframework.e2e.packaged.generated"
    }
}
