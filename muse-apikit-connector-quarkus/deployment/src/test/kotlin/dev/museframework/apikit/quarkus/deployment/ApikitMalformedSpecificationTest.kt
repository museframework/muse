package dev.museframework.apikit.quarkus.deployment

import dev.museframework.apikit.ApiSpecificationParseException
import dev.museframework.apikit.api.config.ApikitConfig
import dev.museframework.dsl.annotation.MuseConfig
import dev.museframework.dsl.api.config.MuseConfigDefinition
import io.quarkus.test.QuarkusUnitTest
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.shrinkwrap.api.ShrinkWrap
import org.jboss.shrinkwrap.api.asset.StringAsset
import org.jboss.shrinkwrap.api.spec.JavaArchive
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class ApikitMalformedSpecificationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val unitTest: QuarkusUnitTest =
            QuarkusUnitTest()
                .setArchiveProducer {
                    ShrinkWrap.create(JavaArchive::class.java)
                        .addClass(MalformedApikitSpecificationConfig::class.java)
                        .addAsResource(
                            StringAsset("title: Missing RAML header"),
                            "api/malformed.raml",
                        )
                }.assertException { failure ->
                    val specificationFailure =
                        generateSequence(failure) { it.cause }
                            .firstOrNull {
                                it.javaClass.name == ApiSpecificationParseException::class.java.name
                            }

                    assertNotNull(specificationFailure)
                    assertTrue(
                        specificationFailure!!.message.orEmpty().contains("missing #%RAML 1.0 header"),
                    )
                }
    }

    @Test
    fun `malformed specification fails Quarkus startup`() {
        fail<Unit>("The test method must not run when startup fails as expected")
    }
}

@MuseConfig
@ApplicationScoped
class MalformedApikitSpecificationConfig : MuseConfigDefinition() {
    val api =
        ApikitConfig(
            name = "malformed-spec",
            docName = "malformed-spec",
            spec = "api/malformed.raml",
        )
}
