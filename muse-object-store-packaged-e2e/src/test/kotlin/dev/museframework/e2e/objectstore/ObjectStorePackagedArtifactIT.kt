package dev.museframework.e2e.objectstore

import dev.museframework.dsl.api.config.MuseConfigDefinition
import dev.museframework.dsl.api.model.flow.MuseFlow
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.reflect.full.memberProperties

class ObjectStorePackagedArtifactIT {
    @Test
    fun `generated object store definition compiles and exposes its seven operation flow`() {
        val type = Class.forName("dev.museframework.e2e.objectstore.generated.ObjectStoreGeneratedConfig")
        assertTrue(MuseConfigDefinition::class.java.isAssignableFrom(type))
        val definition = type.getDeclaredConstructor().newInstance()
        assertNotNull(definition)
        val flow = definition::class.memberProperties.map { it.getter.call(definition) }
            .filterIsInstance<MuseFlow.Flow>().single { it.name == "roundtrip" }
        assertEquals(
            setOf("ObjectStoreClearConfig", "ObjectStoreContainsConfig", "ObjectStoreRemoveConfig", "ObjectStoreRetrieveConfig", "ObjectStoreRetrieveAllConfig", "ObjectStoreRetrieveAllKeysConfig", "ObjectStoreStoreConfig"),
            flow.pipeline.map { it.javaClass.simpleName }.toSet(),
        )
    }

    @Test
    fun `packaged jar owns Object Store runtime H2 and registrar without foreign connectors or test fixtures`() {
        JarFile(Path.of(requireNotNull(System.getProperty("packaged.e2e.jar"))).toFile()).use { archive ->
            val libraries = archive.entries().asSequence().map { it.name }.filter { it.startsWith("BOOT-INF/lib/") }.toList()
            assertTrue(libraries.any { it.substringAfterLast('/').matches(Regex("muse-object-store-connector-[0-9].*")) })
            assertTrue(libraries.any { it.substringAfterLast('/').startsWith("h2-") })
            listOf("muse-http-connector-", "muse-file-connector-", "muse-sftp-connector-", "muse-db-connector-", "junit-", "muse-packaged-e2e-testkit-").forEach { prefix ->
                assertTrue(libraries.none { it.substringAfterLast('/').startsWith(prefix) }, "Unexpected dependency: $prefix")
            }
            val runtimeLibrary = libraries.single { it.substringAfterLast('/').matches(Regex("muse-object-store-connector-[0-9].*")) }
            val runtimeBytes = archive.getInputStream(archive.getJarEntry(runtimeLibrary)).use { it.readBytes() }
            java.util.jar.JarInputStream(runtimeBytes.inputStream()).use { runtime ->
                var registrar: String? = null
                while (true) {
                    val entry = runtime.nextJarEntry ?: break
                    if (entry.name == "META-INF/services/dev.museframework.engine.api.spi.MuseComponentRegistrar") {
                        registrar = runtime.readBytes().toString(Charsets.UTF_8).trim()
                    }
                }
                assertEquals("dev.museframework.connectors.objectstore.ObjectStoreConnectorRegistrar", registrar)
            }
        }
    }
}
