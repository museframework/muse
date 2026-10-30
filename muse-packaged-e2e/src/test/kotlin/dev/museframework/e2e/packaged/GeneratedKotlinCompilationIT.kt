package dev.museframework.e2e.packaged

import dev.museframework.dsl.api.config.MuseConfigDefinition
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GeneratedKotlinCompilationIT {
    @Test
    fun `generated configuration and application classes load from compiled bytecode`() {
        val generatedConfigClasses =
            listOf(
                "ChoiceErrorConfig",
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

    private fun loadGeneratedClass(simpleName: String): Class<*> =
        Class.forName("$GENERATED_PACKAGE.$simpleName")

    private companion object {
        const val GENERATED_PACKAGE = "dev.museframework.e2e.packaged.generated"
    }
}
