package dev.museframework.architecture

import dev.museframework.http.api.client.HttpClientInitializer
import dev.museframework.http.api.server.HttpListenerServerInitializer
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.Architectures
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.ServiceLoader

class ArchitectureTest {
    companion object {
        private val ENGINE_IMPLEMENTATION_PACKAGES =
            arrayOf(
                "dev.museframework.engine.app..",
                "dev.museframework.engine.components..",
                "dev.museframework.engine.context..",
                "dev.museframework.engine.dataweave..",
                "dev.museframework.engine.dispatcher..",
                "dev.museframework.engine.execution..",
                "dev.museframework.engine.expression..",
                "dev.museframework.engine.factory..",
                "dev.museframework.engine.properties..",
            )

        private lateinit var allClasses: JavaClasses

        private lateinit var extensionBoundaryClasses: JavaClasses

        @BeforeAll
        @JvmStatic
        fun setup() {
            allClasses =
                ClassFileImporter().importPackages(
                    "dev.museframework.dsl",
                    "dev.museframework.engine.api",
                    "dev.museframework.engine",
                    "dev.museframework.http.api",
                    "dev.museframework.connectors.http",
                    "dev.museframework.connectors.http.spring",
                    "dev.museframework.sftp.api",
                    "dev.museframework.connectors.sftp",
                    "dev.museframework.sftp.testkit",
                    "dev.museframework.db.api",
                    "dev.museframework.connectors.db",
                    "dev.museframework.file.api",
                    "dev.museframework.connectors.file",
                    "dev.museframework.springboot",
                    "dev.museframework.babelfish.spi",
                    "dev.museframework.babelfish.mappers.core",
                    "dev.museframework.babelfish.mappers.http",
                    "dev.museframework.babelfish.mappers.apikit",
                    "dev.museframework.babelfish.mappers.sftp",
                    "dev.museframework.babelfish.mappers.db",
                    "dev.museframework.babelfish.mappers.file",
                    "dev.museframework.plugin.core",
                )
            extensionBoundaryClasses =
                ClassFileImporter().importPackages(
                    "dev.museframework.test.policy",
                    "dev.museframework.apikit",
                    "dev.museframework.connectors.http.client",
                    "dev.museframework.connectors.http.processor",
                )
        }
    }

    /**
     * Constraint 1: DSL Purity
     * muse-dsl must NOT depend on any engine packages.
     */
    @Test
    fun `dsl should not depend on engine packages`() {
        noClasses()
            .that()
            .resideInAPackage("dev.museframework.dsl..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "dev.museframework.engine..",
                "dev.museframework.connectors..",
            ).because("muse-dsl must be pure - no engine dependencies")
            .check(allClasses)
    }

    /**
     * Constraint 2: Engine API Minimal Dependencies
     * muse-engine-api must NOT depend on engine implementation packages.
     */
    @Test
    fun `engine api should not depend on engine implementation`() {
        noClasses()
            .that()
            .resideInAPackage("dev.museframework.engine.api..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("dev.museframework.engine.execution..")
            .because("muse-engine-api must not depend on engine implementation")
            .check(allClasses)
    }

    /**
     * Constraint 3: ADR-007 - Connectors Must Not Depend on Engine Implementation
     * HTTP connectors must NOT depend on engine implementation packages.
     */
    @Test
    fun `http connector should not depend on engine implementation packages`() {
        noClasses()
            .that()
            .resideInAnyPackage(
                "dev.museframework.connectors.http..",
                "dev.museframework.connectors.http.spring..",
            ).should()
            .dependOnClassesThat()
            .resideInAnyPackage(*ENGINE_IMPLEMENTATION_PACKAGES)
            .because("ADR-007: Connectors must depend only on API, not implementation")
            .check(allClasses)
    }

    /**
     * Constraint 4: HTTP Connector API - No Engine Implementation Dependency
     * muse-http-connector-api must NOT depend on engine implementation.
     */
    @Test
    fun `http connector api should not depend on engine implementation`() {
        noClasses()
            .that()
            .resideInAPackage("dev.museframework.http.api..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(*ENGINE_IMPLEMENTATION_PACKAGES)
            .because("HTTP Connector API must not depend on engine implementation")
            .check(allClasses)
    }

    @Test
    fun `http api should not depend on runtime or Spring implementations`() {
        noClasses()
            .that()
            .resideInAPackage("dev.museframework.http.api..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("dev.museframework.connectors.http..")
            .because("the canonical HTTP API must remain adapter-neutral")
            .check(allClasses)
    }

    @Test
    fun `http mappers should not depend on runtime or Spring implementations`() {
        noClasses()
            .that()
            .resideInAPackage("dev.museframework.babelfish.mappers.http..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("dev.museframework.connectors.http..")
            .because("Babelfish HTTP mappers must target only the canonical HTTP API")
            .check(allClasses)
    }

    @Test
    fun `http runtime should not depend on the Spring adapter`() {
        noClasses()
            .that()
            .resideInAnyPackage(
                "dev.museframework.connectors.http.client..",
                "dev.museframework.connectors.http.processor..",
            ).should()
            .dependOnClassesThat()
            .resideInAPackage("dev.museframework.connectors.http.spring..")
            .because("the runtime connector must remain independent of its Spring adapter")
            .check(allClasses)
    }
    @Test
    fun `database connector should not depend on engine implementation packages`() {
        noClasses()
            .that()
            .resideInAPackage("dev.museframework.connectors.db..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(*ENGINE_IMPLEMENTATION_PACKAGES)
            .because("database runtime must depend only on Engine API")
            .check(allClasses)
    }

    @Test
    fun `database api should not depend on runtime implementation`() {
        noClasses()
            .that()
            .resideInAPackage("dev.museframework.db.api..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("dev.museframework.connectors.db..")
            .because("the database API must remain framework-neutral")
            .check(allClasses)
    }

    @Test
    fun `database mappers should depend on the API rather than runtime`() {
        noClasses()
            .that()
            .resideInAPackage("dev.museframework.babelfish.mappers.db..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("dev.museframework.connectors.db..")
            .because("Babelfish database mappers must target only the public database API")
            .check(allClasses)
    }


    @Test
    fun `sftp API and runtime preserve connector dependency boundaries`() {
        noClasses()
            .that()
            .resideInAPackage("dev.museframework.sftp.api..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "dev.museframework.connectors.sftp..",
                *ENGINE_IMPLEMENTATION_PACKAGES,
            ).because("the SFTP API must remain declarative and runtime-neutral")
            .check(allClasses)

        noClasses()
            .that()
            .resideInAPackage("dev.museframework.connectors.sftp..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(*ENGINE_IMPLEMENTATION_PACKAGES)
            .because("SFTP runtime components must depend only on Muse DSL and Engine API contracts")
            .check(allClasses)
    }

    @Test
    fun `file connector should not depend on engine implementation packages`() {
        noClasses()
            .that()
            .resideInAPackage("dev.museframework.connectors.file..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(*ENGINE_IMPLEMENTATION_PACKAGES)
            .because("File connector runtime must depend only on stable Muse APIs")
            .check(allClasses)
    }

    @Test
    fun `sftp testkit remains independent from Muse runtime modules`() {
        noClasses()
            .that()
            .resideInAPackage("dev.museframework.sftp.testkit..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "dev.museframework.dsl..",
                "dev.museframework.engine..",
                "dev.museframework.connectors.sftp..",
            ).because("the embedded SFTP fixture must be reusable without a Muse application")
            .check(allClasses)
    }

    @Test
    fun `file connector API should remain runtime neutral`() {
        noClasses()
            .that()
            .resideInAPackage("dev.museframework.file.api..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                *ENGINE_IMPLEMENTATION_PACKAGES,
                "dev.museframework.connectors.file..",
            ).because("File connector API must not depend on its runtime or the engine implementation")
            .check(allClasses)
    }

    @Test
    fun `sftp mappers should not depend on runtime implementations`() {
        noClasses()
            .that()
            .resideInAPackage("dev.museframework.babelfish.mappers.sftp..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("dev.museframework.connectors.sftp..")
            .because("Babelfish SFTP mappers must target only the canonical SFTP API")
            .check(allClasses)
    }

    @Test
    fun `file mappers should not depend on runtime implementations`() {
        noClasses()
            .that()
            .resideInAPackage("dev.museframework.babelfish.mappers.file..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("dev.museframework.connectors.file..")
            .because("Babelfish File mappers must target only the canonical File API")
            .check(allClasses)
    }

    @Test
    fun `deleted legacy HTTP API namespaces should remain absent`() {
        val forbiddenPackages =
            setOf(
                "dev.museframework.connectors.http.config",
                "dev.museframework.connectors.http.dsl",
                "dev.museframework.connectors.http.model",
            )
        val forbiddenClasses =
            setOf(
                "dev.museframework.connectors.http.client.HttpClientInitializer",
                "dev.museframework.connectors.http.processor.HttpClient",
                "dev.museframework.connectors.http.processor.HttpOutboundRequest",
            )

        val violations =
            allClasses
                .filter { javaClass ->
                    forbiddenPackages.any { forbidden ->
                        javaClass.packageName == forbidden || javaClass.packageName.startsWith("$forbidden.")
                    } || javaClass.name in forbiddenClasses
                }.map { it.name }

        assertTrue(violations.isEmpty(), "Legacy HTTP classes were reintroduced: $violations")
    }

    @Test
    fun `Spring service loader metadata should resolve canonical HTTP providers`() {
        val listenerProviders =
            ServiceLoader
                .load(HttpListenerServerInitializer::class.java)
                .map { it::class.java.name }
                .toSet()
        val clientProviders =
            ServiceLoader
                .load(HttpClientInitializer::class.java)
                .map { it::class.java.name }
                .toSet()

        assertEquals(
            setOf("dev.museframework.connectors.http.spring.SpringHttpListenerServerInitializer"),
            listenerProviders,
        )
        assertEquals(
            setOf("dev.museframework.connectors.http.spring.SpringHttpClientInitializer"),
            clientProviders,
        )

        val autoConfigurations =
            resourceEntries("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
        assertTrue(
            "dev.museframework.connectors.http.spring.MuseHttpAutoConfiguration" in autoConfigurations,
            "The Spring HTTP auto-configuration metadata is missing",
        )
        autoConfigurations.forEach { Class.forName(it) }
    }

    /**
     * Constraint 5: No Engine Imports in DSL
     * muse-dsl must NOT import from dev.museframework.engine.api.* or dev.museframework.engine.*
     */
    @Test
    fun `dsl should not import from engine packages`() {
        noClasses()
            .that()
            .resideInAPackage("dev.museframework.dsl..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "dev.museframework.engine.api..",
                "dev.museframework.engine..",
            ).because("muse-dsl must be pure - no engine dependencies")
            .check(allClasses)
    }

    /**
     * Constraint 6: Layered Architecture
     * Verify the dependency direction follows the layered architecture.
     * DSL is the foundation layer that all other layers depend on.
     * Engine API is the extension point for connectors.
     */
    @Test
    fun `layered architecture should enforce dependency direction`() {
        Architectures
            .layeredArchitecture()
            .consideringAllDependencies()
            .layer("DSL")
            .definedBy("dev.museframework.dsl..")
            .layer("Engine API")
            .definedBy("dev.museframework.engine.api..")
            .layer("Engine")
            .definedBy(*ENGINE_IMPLEMENTATION_PACKAGES)
            .layer("SFTP API")
            .definedBy("dev.museframework.sftp.api..")
            .layer("SFTP Runtime")
            .definedBy("dev.museframework.connectors.sftp..")
            .layer("HTTP API")
            .definedBy("dev.museframework.http.api..")
            .layer("HTTP Runtime")
            .definedBy(
                "dev.museframework.connectors.http.client..",
                "dev.museframework.connectors.http.processor..",
            )
            .layer("File API")
            .definedBy("dev.museframework.file.api..")
            .layer("File Runtime")
            .definedBy("dev.museframework.connectors.file..")
            .layer("HTTP Connector Spring")
            .definedBy("dev.museframework.connectors.http.spring..")
            .layer("HTTP Mappers")
            .definedBy("dev.museframework.babelfish.mappers.http..")
            .layer("Database API")
            .definedBy("dev.museframework.db.api..")
            .layer("Database Runtime")
            .definedBy("dev.museframework.connectors.db..")
            .layer("Database Mappers")
            .definedBy("dev.museframework.babelfish.mappers.db..")
            .layer("File Mappers")
            .definedBy("dev.museframework.babelfish.mappers.file..")
            .layer("Spring Boot")
            .definedBy("dev.museframework.springboot..")
            .whereLayer("Engine API")
            .mayOnlyBeAccessedByLayers(
                "Engine",
                "HTTP API",
                "HTTP Runtime",
                "HTTP Connector Spring",
                "SFTP Runtime",
                "Database API",
                "Database Runtime",
                "Database Mappers",
                "File API",
                "File Runtime",
                "Spring Boot",
            ).check(allClasses)
    }

    @Test
    fun `babelfish spi and mapper providers should not depend on maven plugin packages`() {
        noClasses()
            .that()
            .resideInAnyPackage(
                "dev.museframework.babelfish.spi..",
                "dev.museframework.babelfish.mappers.core..",
                "dev.museframework.babelfish.mappers.http..",
                "dev.museframework.babelfish.mappers.apikit..",
                "dev.museframework.babelfish.mappers.sftp..",
                "dev.museframework.babelfish.mappers.db..",
                "dev.museframework.babelfish.mappers.file..",
            ).should()
            .dependOnClassesThat()
            .resideInAPackage("dev.museframework.plugin..")
            .because("mapper modules must be usable outside the Maven plugin")
            .check(allClasses)
    }

    @Test
    fun `plugin core should not depend on optional connector mapper packages`() {
        noClasses()
            .that()
            .resideInAPackage("dev.museframework.plugin.core..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "dev.museframework.babelfish.mappers.http..",
                "dev.museframework.babelfish.mappers.apikit..",
                "dev.museframework.babelfish.mappers.sftp..",
                "dev.museframework.babelfish.mappers.db..",
                "dev.museframework.babelfish.mappers.file..",
            ).because("plugin core must discover optional mappers via SPI, not direct imports")
            .check(allClasses)
    }

    @Test
    fun `core mapper jar packages provider metadata`() {
        val providers = classpathServiceResource("muse-babelfish-core-mappers")
        assertEquals(
            setOf("dev.museframework.babelfish.mappers.core.CoreMapperProvider"),
            providers.toSet(),
        )
    }

    @Test
    fun `http mapper jar packages provider metadata`() {
        val providers = classpathServiceResource("muse-http-babelfish-mappers")
        assertEquals(
            setOf("dev.museframework.babelfish.mappers.http.HttpMapperProvider"),
            providers.toSet(),
        )
    }

    @Test
    fun `apikit mapper jar packages provider metadata`() {
        val providers = classpathServiceResource("muse-apikit-babelfish-mappers")
        assertEquals(
            setOf("dev.museframework.babelfish.mappers.apikit.ApikitMapperProvider"),
            providers.toSet(),
        )
    }
    @Test
    fun `database mapper jar packages provider metadata`() {
        val providers = classpathServiceResource("muse-db-babelfish-mappers")
        assertEquals(
            setOf("dev.museframework.babelfish.mappers.db.DbMapperProvider"),
            providers.toSet(),
        )
    }


    @Test
    fun `sftp mapper jar packages provider metadata`() {
        val providers = classpathServiceResource("muse-sftp-babelfish-mappers")
        assertEquals(
            setOf("dev.museframework.babelfish.mappers.sftp.SftpMapperProvider"),
            providers.toSet(),
        )
    }

    @Test
    fun `file mapper jar packages provider metadata`() {
        val providers = classpathServiceResource("muse-file-babelfish-mappers")
        assertEquals(
            setOf("dev.museframework.babelfish.mappers.file.FileMapperProvider"),
            providers.toSet(),
        )
    }

    /**
     * Constraint 7: Extension boundary
     * The neutral external policy fixture, APIKit and the neutral HTTP runtime must not depend on
     * engine implementation packages: they are extension consumers of muse-engine-api only.
     */
    @Test
    fun `extension production code apikit and the neutral http runtime should not depend on engine implementation`() {
        noClasses()
            .that()
            .resideInAnyPackage(
                "dev.museframework.test.policy..",
                "dev.museframework.apikit..",
                "dev.museframework.connectors.http.client..",
                "dev.museframework.connectors.http.processor..",
            ).should()
            .dependOnClassesThat()
            .resideInAnyPackage(*ENGINE_IMPLEMENTATION_PACKAGES)
            .because(
                "extension artifacts, APIKit and the neutral HTTP runtime depend only on muse-dsl and " +
                    "muse-engine-api, never on the engine implementation",
            )
            .check(extensionBoundaryClasses)
    }

    @Test
    fun `extension fixture registrar is discoverable from its own jar`() {
        val registrars =
            ServiceLoader
                .load(dev.museframework.engine.api.spi.MuseComponentRegistrar::class.java)
                .map { it.javaClass.name }
                .toSet()

        assertTrue(
            "dev.museframework.test.policy.DefaultOnFailureRegistrar" in registrars,
            "The external fixture registrar must be discoverable through its service descriptor: $registrars",
        )

        val fixtureClasses =
            listOf(
                "dev.museframework.test.policy.DefaultOnFailureConfig",
                "dev.museframework.test.policy.DefaultOnFailurePolicy",
                "dev.museframework.test.policy.DefaultOnFailureRegistrar",
            ).map { Class.forName(it) }

        assertTrue(
            fixtureClasses.all { clazz ->
                clazz.protectionDomain?.codeSource?.location?.toString()?.contains("muse-policy-extension-fixture") == true
            },
            "The extension fixture must be loaded from its own artifact: " +
                fixtureClasses.map { it.protectionDomain?.codeSource?.location?.toString() },
        )
    }

    @Test
    fun `sftp runtime jar packages component registrar metadata`() {
        val resourcePath = "META-INF/services/dev.museframework.engine.api.spi.MuseComponentRegistrar"
        val providers =
            javaClass.classLoader
                .getResources(resourcePath)
                .asSequence()
                .filter { url ->
                    val path = url.toString()
                    path.contains("/muse-sftp-connector/") || path.contains("/muse-sftp-connector-")
                }.flatMap { url ->
                    url.openStream().bufferedReader().use { reader -> reader.readLines() }.asSequence()
                }.map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .toSet()

        assertEquals(
            setOf("dev.museframework.connectors.sftp.SftpConnectorRegistrar"),
            providers,
        )
    }

    private fun classpathServiceResource(moduleDir: String): List<String> {
        val resourcePath = "META-INF/services/dev.museframework.babelfish.spi.BabelfishMapperProvider"
        val expectedProvider =
            when (moduleDir) {
                "muse-babelfish-core-mappers" -> "dev.museframework.babelfish.mappers.core.CoreMapperProvider"
                "muse-http-babelfish-mappers" -> "dev.museframework.babelfish.mappers.http.HttpMapperProvider"
                "muse-apikit-babelfish-mappers" -> "dev.museframework.babelfish.mappers.apikit.ApikitMapperProvider"
                "muse-sftp-babelfish-mappers" -> "dev.museframework.babelfish.mappers.sftp.SftpMapperProvider"
                "muse-db-babelfish-mappers" -> "dev.museframework.babelfish.mappers.db.DbMapperProvider"
                "muse-file-babelfish-mappers" -> "dev.museframework.babelfish.mappers.file.FileMapperProvider"
                else -> throw AssertionError("Unknown mapper module: $moduleDir")
            }

        val urls =
            javaClass.classLoader
                .getResources(resourcePath)
                .asSequence()
                .filter { url ->
                    val path = url.toString()
                    path.contains("/$moduleDir/") || path.contains("/$moduleDir-")
                }
                .toList()

        assertTrue(
            urls.isNotEmpty(),
            "Provider metadata for $moduleDir not found on test classpath",
        )

        val providers =
            urls.flatMap { url ->
                url.openStream().bufferedReader().use { reader ->
                    reader.readLines()
                        .map { it.substringBefore('#').trim() }
                        .filter { it.isNotEmpty() }
                }
            }

        assertTrue(
            expectedProvider in providers,
            "Expected $expectedProvider in $moduleDir provider metadata, got $providers",
        )
        return providers
    }

    private fun resourceEntries(path: String): Set<String> =
        javaClass.classLoader
            .getResources(path)
            .asSequence()
            .flatMap { url ->
                url.openStream().bufferedReader().use { reader -> reader.readLines() }.asSequence()
            }.map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .toSet()
}
