package dev.museframework.apikit.quarkus.deployment

import dev.museframework.apikit.ApiSpecificationNotFoundException
import dev.museframework.apikit.api.config.ApikitConfig
import dev.museframework.dsl.annotation.MuseConfig
import dev.museframework.dsl.api.config.MuseConfigDefinition
import io.quarkus.test.QuarkusUnitTest
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.shrinkwrap.api.ShrinkWrap
import org.jboss.shrinkwrap.api.spec.JavaArchive
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class ApikitMissingSpecificationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val unitTest: QuarkusUnitTest =
            QuarkusUnitTest()
                .setArchiveProducer {
                    ShrinkWrap.create(JavaArchive::class.java)
                        .addClass(MissingApikitSpecificationConfig::class.java)
                }.assertException { failure ->
                    val specificationFailure =
                        generateSequence(failure) { it.cause }
                            .firstOrNull {
                                it.javaClass.name == ApiSpecificationNotFoundException::class.java.name
                            }

                    assertNotNull(specificationFailure)
                    assertTrue(
                        specificationFailure!!.message.orEmpty().contains("api/missing.raml"),
                    )
                }
    }

    @Test
    fun `missing specification fails Quarkus startup even when validations are disabled`() {
        fail<Unit>("The test method must not run when startup fails as expected")
    }
}

@MuseConfig
@ApplicationScoped
class MissingApikitSpecificationConfig : MuseConfigDefinition() {
    val api =
        ApikitConfig(
            name = "missing-spec",
            docName = "missing-spec",
            spec = "api/missing.raml",
            disableValidations = true,
        )
}
