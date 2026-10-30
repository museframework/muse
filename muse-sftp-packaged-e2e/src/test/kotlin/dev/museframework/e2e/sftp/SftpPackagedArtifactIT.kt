package dev.museframework.e2e.sftp

import org.junit.jupiter.api.Test
import java.util.jar.JarFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

class SftpPackagedArtifactIT {
    @Test
    fun `packaged JAR includes SFTP and HTTP runtimes and excludes foreign connectors and test helpers`() {
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
                    library.startsWith("muse-sftp-connector-") &&
                        !library.startsWith("muse-sftp-connector-api-") &&
                        !library.startsWith("muse-sftp-connector-testkit-")
                },
                "JAR must include muse-sftp-connector runtime (not only API/testkit)",
            )
            assertTrue(
                libraries.any { it.startsWith("muse-http-connector-spring-") },
                "JAR must include muse-http-connector-spring adapter",
            )

            val forbiddenPrefixes =
                listOf(
                    "muse-db-connector-",
                    "h2-",
                    "muse-file-connector-",
                    "muse-policy-extension-fixture-",
                    "muse-sftp-connector-testkit-",
                    "muse-packaged-e2e-testkit-",
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
