package dev.museframework.e2e.file

import dev.museframework.dsl.api.config.MuseConfigDefinition
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GeneratedFileCompilationIT {
    @Test
    fun `generated CSV streaming fixture loads from compiled bytecode`() {
        val config = loadGeneratedClass("CsvStreamingConfig")
        assertTrue(MuseConfigDefinition::class.java.isAssignableFrom(config))
        assertNotNull(config.getDeclaredConstructor().newInstance())
    }

    @Test
    fun `generated file configuration and application classes load from compiled bytecode`() {
        val generatedConfig = loadGeneratedClass("FileGeneratedConfig")
        assertTrue(
            MuseConfigDefinition::class.java.isAssignableFrom(generatedConfig),
            "${generatedConfig.name} must extend MuseConfigDefinition",
        )
        assertNotNull(generatedConfig.getDeclaredConstructor().newInstance())

        val applicationClass = loadGeneratedClass("MuseFilePackagedE2eApplicationKt")
        val mainMethod = applicationClass.getDeclaredMethod("main", Array<String>::class.java)
        assertTrue(Modifier.isPublic(mainMethod.modifiers))
        assertTrue(Modifier.isStatic(mainMethod.modifiers))
        assertEquals(Void.TYPE, mainMethod.returnType)

        val generatedSource =
            Path.of("target/generated-sources/muse")
                .resolve(GENERATED_PACKAGE.replace('.', '/'))
                .resolve("MuseFilePackagedE2eApplication.kt")
        assertTrue(Files.isRegularFile(generatedSource), "Generated application source must exist: $generatedSource")
        val sourceText = Files.readString(generatedSource)
        assertTrue(
            sourceText.contains("WebApplicationType.SERVLET"),
            "Generated entry point must retain SERVLET web environment for compilation evidence",
        )
        assertTrue(
            sourceText.contains("dev.museframework.e2e.file.generated"),
            "Generated entry point must scan the generated package",
        )

        val configSource =
            Path.of("target/generated-sources/muse")
                .resolve(GENERATED_PACKAGE.replace('.', '/'))
                .resolve("FileGeneratedConfig.kt")
        assertTrue(Files.isRegularFile(configSource), "Generated file config source must exist: $configSource")
        val configText = Files.readString(configSource)
        assertTrue(configText.contains("generated"), "Generated file connection must use relative working directory")
        assertTrue(configText.contains("generated-file-flow"), "Generated file flow name must be preserved")
    }

    private fun loadGeneratedClass(simpleName: String): Class<*> =
        Class.forName("$GENERATED_PACKAGE.$simpleName")

    private companion object {
        const val GENERATED_PACKAGE = "dev.museframework.e2e.file.generated"
    }
}
