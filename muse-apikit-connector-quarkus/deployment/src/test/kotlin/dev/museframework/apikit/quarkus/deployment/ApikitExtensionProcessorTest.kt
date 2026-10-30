package dev.museframework.apikit.quarkus.deployment

import dev.museframework.apikit.api.config.ApikitConfig
import dev.museframework.apikit.api.model.ApiSpecification
import dev.museframework.dsl.annotation.MuseConfig
import dev.museframework.dsl.api.config.MuseConfigDefinition
import dev.museframework.engine.app.MuseApplication
import io.quarkus.test.QuarkusUnitTest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.shrinkwrap.api.ShrinkWrap
import org.jboss.shrinkwrap.api.asset.StringAsset
import org.jboss.shrinkwrap.api.spec.JavaArchive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class ApikitExtensionProcessorTest {
    companion object {
        @JvmField
        @RegisterExtension
        val unitTest: QuarkusUnitTest =
            QuarkusUnitTest().setArchiveProducer {
                ShrinkWrap.create(JavaArchive::class.java)
                    .addClass(ValidApikitConfig::class.java)
                    .addAsResource(
                        StringAsset(
                            """
                            #%RAML 1.0
                            title: Quarkus Extension Test
                            /health:
                              get:
                            """.trimIndent(),
                        ),
                        "api/extension-test.raml",
                    )
            }
    }

    @Inject
    lateinit var application: MuseApplication

    @Test
    fun `deployment discovers registrar and loads packaged RAML resource`() {
        val specification =
            application.context.properties["apikit.extension-test.spec"] as ApiSpecification

        assertEquals(listOf("/health"), specification.resources.map { it.path })
        assertEquals(true, application.context.properties["apikit.extension-test.validationsEnabled"])
    }

}

@MuseConfig
@ApplicationScoped
class ValidApikitConfig : MuseConfigDefinition() {
    val api =
        ApikitConfig(
            name = "extension-test",
            docName = "extension-test",
            spec = "api/extension-test.raml",
        )
}
