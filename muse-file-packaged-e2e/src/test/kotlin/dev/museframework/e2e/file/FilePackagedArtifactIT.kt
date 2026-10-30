package dev.museframework.e2e.file

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.assertTrue

class FilePackagedArtifactIT {
    @Test
    fun `packaged JAR includes file runtime and excludes foreign connectors and test helpers`() {
        val jar = Path.of(requiredProperty("packaged.e2e.jar")).toAbsolutePath()
        assertTrue(Files.isRegularFile(jar), "Packaged JAR must exist: $jar")

        JarFile(jar.toFile()).use { archive ->
            val entries = archive.entries().asSequence().map { it.name }.toSet()
            val libraries =
                entries
                    .filter { it.startsWith("BOOT-INF/lib/") }
                    .map { it.removePrefix("BOOT-INF/lib/") }
                    .toList()

            assertTrue(
                libraries.any { library ->
                    library.startsWith("muse-file-connector-") &&
                        !library.startsWith("muse-file-connector-api-")
                },
                "JAR must include muse-file-connector runtime (not only API)",
            )

            val forbiddenPrefixes =
                listOf(
                    "muse-http-connector-",
                    "muse-sftp-connector-",
                    "muse-db-connector-",
                    "h2-",
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
