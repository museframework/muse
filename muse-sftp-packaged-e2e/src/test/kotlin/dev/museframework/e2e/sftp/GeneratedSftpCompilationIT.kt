package dev.museframework.e2e.sftp

import dev.museframework.dsl.api.config.MuseConfigDefinition
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GeneratedSftpCompilationIT {
    @Test
    fun `generated SFTP configuration and application classes load from compiled bytecode`() {
        val generatedConfig = loadGeneratedClass("SftpGeneratedConfig")
        assertTrue(
            MuseConfigDefinition::class.java.isAssignableFrom(generatedConfig),
            "${generatedConfig.name} must extend MuseConfigDefinition",
        )
        assertNotNull(generatedConfig.getDeclaredConstructor().newInstance())

        val applicationClass = loadGeneratedClass("MuseSftpPackagedE2eApplicationKt")
        val mainMethod = applicationClass.getDeclaredMethod("main", Array<String>::class.java)
        assertTrue(Modifier.isPublic(mainMethod.modifiers))
        assertTrue(Modifier.isStatic(mainMethod.modifiers))
        assertEquals(Void.TYPE, mainMethod.returnType)

        val generatedSource =
            Path.of("target/generated-sources/muse")
                .resolve(GENERATED_PACKAGE.replace('.', '/'))
                .resolve("SftpGeneratedConfig.kt")
        assertTrue(Files.isRegularFile(generatedSource), "Generated SFTP source must exist: $generatedSource")
        val sourceText = Files.readString(generatedSource)
        assertTrue(sourceText.contains("18822"), "Generated SFTP config must retain literal port 18822")
        assertTrue(sourceText.contains("generated-sftp"), "Generated SFTP config must retain distinct connection name")
    }

    private fun loadGeneratedClass(simpleName: String): Class<*> =
        Class.forName("$GENERATED_PACKAGE.$simpleName")

    private companion object {
        const val GENERATED_PACKAGE = "dev.museframework.e2e.sftp.generated"
    }
}
