package dev.museframework.e2e.file

import dev.museframework.dsl.api.config.MuseConfigDefinition
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.model.config.ConfigRef
import dev.museframework.e2e.file.generated.FileGeneratedConfig
import dev.museframework.file.api.config.FileCopyConfig
import dev.museframework.file.api.config.FileCreateDirectoryConfig
import dev.museframework.file.api.config.FileDeleteConfig
import dev.museframework.file.api.config.FileListConfig
import dev.museframework.file.api.config.FileListenerConfig
import dev.museframework.file.api.config.FileMoveConfig
import dev.museframework.file.api.config.FileReadConfig
import dev.museframework.file.api.config.FileRenameConfig
import dev.museframework.file.api.config.FileWriteConfig
import dev.museframework.file.api.model.SortOrder
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class GeneratedFileCompilationIT {
    @Test
    fun `compiled file DSL retains references options and exact paths`() {
        val config = FileGeneratedConfig()
        assertEquals("generated", config.generatedFiles.workingDirectory)
        val flow = config.generatedFileFlow
        assertEquals("generated-file-flow", flow.docId)
        val listener = assertIs<FileListenerConfig>(flow.source)
        assertEquals(ConfigRef.ByName("generated-files"), listener.connectionConfig)
        assertEquals(25L, listener.pollingFrequencyMillis)
        assertEquals(0L, listener.startDelayMillis)
        assertEquals("archive", listener.moveToDirectory)
        assertEquals("processed.txt", listener.renameTo)
        assertTrue(listener.overwrite)
        assertTrue(listener.applyPostActionWhenFailed)
        assertNull(listener.outputMimeType)
        assertNull(listener.encoding)
        val write = assertIs<FileWriteConfig>(flow.pipeline.single())
        assertEquals("output/copied.txt", write.path)
        assertEquals("#[payload]", write.content)

        val options = config.fileOptions
        assertEquals("file-options-id", options.docId)
        val pipeline = options.pipeline
        assertEquals(7, pipeline.size)
        val read = assertIs<FileReadConfig>(pipeline[0])
        assertEquals("inbox\\\$batch\\input.csv", read.path)
        assertEquals("application/csv; streaming=true", read.outputMimeType)
        assertEquals("", read.encoding)
        assertEquals(TargetConfig("rows", "#[payload]"), read.targetConfig)
        val list = assertIs<FileListConfig>(pipeline[1])
        assertEquals("*.txt", config.textFiles.matcher.filenamePattern)
        assertEquals("*.txt", list.matcher?.filenamePattern)
        assertEquals(2L, list.matcher?.minSize)
        assertEquals(3, list.subset?.limit)
        assertEquals(SortOrder.ASCENDING, list.subset?.order)
        assertEquals("", assertIs<FileCopyConfig>(pipeline[2]).renameTo)
        assertNull(assertIs<FileMoveConfig>(pipeline[3]).renameTo)
        assertEquals("obsolete", assertIs<FileDeleteConfig>(pipeline[4]).path)
        assertEquals("new", assertIs<FileRenameConfig>(pipeline[5]).to)
        assertEquals("created", assertIs<FileCreateDirectoryConfig>(pipeline[6]).directoryPath)
    }

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
