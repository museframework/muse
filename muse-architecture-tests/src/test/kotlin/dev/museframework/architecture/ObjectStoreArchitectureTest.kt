package dev.museframework.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test
import java.util.ServiceLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class ObjectStoreArchitectureTest {
    private val classes = ClassFileImporter().importPackages(
        "dev.museframework.objectstore.api",
        "dev.museframework.connectors.objectstore",
        "dev.museframework.babelfish.mappers.objectstore",
    )

    @Test
    fun `Object Store api stays declarative and does not expose storage engines`() {
        noClasses().that().resideInAPackage("dev.museframework.objectstore.api..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "dev.museframework.engine..", "dev.museframework.connectors..",
                "org.h2..", "com.fasterxml.jackson..", "org.springframework..",
            ).check(classes)
    }

    @Test
    fun `Object Store runtime does not depend on engine implementation or frameworks`() {
        noClasses().that().resideInAPackage("dev.museframework.connectors.objectstore..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "dev.museframework.engine.app..", "dev.museframework.engine.context..",
                "dev.museframework.engine.factory..", "dev.museframework.engine.execution..",
                "dev.museframework.engine.expression..", "dev.museframework.engine.dataweave..",
                "org.springframework..", "io.quarkus..",
            ).check(classes)
    }

    @Test
    fun `Object Store mapper depends on public api without runtime storage or plugin types`() {
        noClasses().that().resideInAPackage("dev.museframework.babelfish.mappers.objectstore..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "dev.museframework.connectors..", "dev.museframework.engine..",
                "dev.museframework.plugin..", "org.h2..", "org.springframework..",
            ).check(classes)
    }

    @Test
    fun `Object Store registrar and mapper provider are discoverable from their family artifacts`() {
        val registrars = ServiceLoader.load(dev.museframework.engine.api.spi.MuseComponentRegistrar::class.java)
            .filter { it.javaClass.name.startsWith("dev.museframework.connectors.objectstore.") }
        assertEquals(listOf("dev.museframework.connectors.objectstore.ObjectStoreConnectorRegistrar"), registrars.map { it.javaClass.name })
        val providers = ServiceLoader.load(dev.museframework.babelfish.spi.BabelfishMapperProvider::class.java)
            .filter { it.id == "object-store" }
        assertEquals(listOf("dev.museframework.babelfish.mappers.objectstore.ObjectStoreMapperProvider"), providers.map { it.javaClass.name })
        (registrars.map { it.javaClass } + providers.map { it.javaClass }).forEach { type ->
            assertTrue(type.protectionDomain.codeSource.location.toString().contains("muse-object-store-"))
        }
    }
}
