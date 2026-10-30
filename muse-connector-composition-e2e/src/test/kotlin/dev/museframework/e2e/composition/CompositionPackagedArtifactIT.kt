package dev.museframework.e2e.composition

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.assertTrue

class CompositionPackagedArtifactIT {
    @Test
    fun `packaged JAR includes file sftp db runtimes and H2 and excludes foreign fixtures`() {
        val jar = Path.of(requiredProperty("packaged.e2e.jar")).toAbsolutePath()
        assertTrue(Files.isRegularFile(jar), "Packaged JAR must exist: $jar")

        JarFile(jar.toFile()).use { archive ->
            val libraries =
                archive.entries().asSequence()
                    .map { it.name }
                    .filter { it.startsWith("BOOT-INF/lib/") }
                    .map { it.removePrefix("BOOT-INF/lib/") }
                    .toList()

            assertTrue(
                libraries.any {
                    it.startsWith("muse-file-connector-") && !it.startsWith("muse-file-connector-api-")
                },
            )
            assertTrue(
                libraries.any {
                    it.startsWith("muse-sftp-connector-") &&
                        !it.startsWith("muse-sftp-connector-api-") &&
                        !it.startsWith("muse-sftp-connector-testkit-")
                },
            )
            assertTrue(
                libraries.any {
                    it.startsWith("muse-db-connector-") && !it.startsWith("muse-db-connector-api-")
                },
            )
            assertTrue(libraries.any { it.startsWith("h2-") })

            val forbiddenPrefixes =
                listOf(
                    "muse-http-connector-",
                    "muse-apikit-connector-",
                    "muse-policy-extension-fixture-",
                    "muse-packaged-e2e-testkit-",
                    "muse-sftp-connector-testkit-",
                    "junit-jupiter-",
                    "junit-platform-",
                )
            forbiddenPrefixes.forEach { prefix ->
                assertTrue(
                    libraries.none { it.startsWith(prefix) },
                    "JAR must not include forbidden library prefix '$prefix'; found=${libraries.filter { it.startsWith(prefix) }}",
                )
            }
        }
    }

    private fun requiredProperty(name: String): String =
        requireNotNull(System.getProperty(name)) { "Required system property is missing: $name" }
}
