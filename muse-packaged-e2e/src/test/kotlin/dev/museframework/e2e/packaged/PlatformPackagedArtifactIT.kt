package dev.museframework.e2e.packaged

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.assertTrue

class PlatformPackagedArtifactIT {
    @Test
    fun `packaged JAR includes HTTP and policy-extension artifacts and excludes family connectors and test helpers`() {
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
                libraries.any { it.startsWith("muse-http-connector-spring-") },
                "JAR must include muse-http-connector-spring adapter",
            )
            assertTrue(
                libraries.any { it.startsWith("muse-policy-extension-fixture-") },
                "JAR must retain the external policy-extension registration artifact",
            )

            val forbiddenPrefixes =
                listOf(
                    "muse-sftp-connector-",
                    "muse-db-connector-",
                    "muse-file-connector-",
                    "h2-",
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
