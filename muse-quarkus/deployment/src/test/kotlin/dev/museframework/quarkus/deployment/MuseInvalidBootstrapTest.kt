package dev.museframework.quarkus.deployment

import dev.museframework.dsl.annotation.MuseConfig
import dev.museframework.dsl.api.config.MuseConfigDefinition
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.flowRef
import dev.museframework.engine.app.MuseBootstrapException
import io.quarkus.test.QuarkusUnitTest
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.shrinkwrap.api.ShrinkWrap
import org.jboss.shrinkwrap.api.spec.JavaArchive
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class MuseInvalidBootstrapTest {
    companion object {
        @JvmField
        @RegisterExtension
        val unitTest: QuarkusUnitTest =
            QuarkusUnitTest()
                .setArchiveProducer {
                    ShrinkWrap.create(JavaArchive::class.java)
                        .addClass(InvalidFlowConfig::class.java)
                }.assertException { failure ->
                    val bootstrapFailure =
                        generateSequence(failure) { it.cause }
                            .firstOrNull { it.javaClass.name == MuseBootstrapException::class.java.name }

                    assertNotNull(bootstrapFailure)
                    assertTrue(
                        bootstrapFailure!!.message.orEmpty().contains("missing-flow"),
                        "The bootstrap error must identify the unresolved flow",
                    )
                }
    }

    @Test
    fun `invalid flow reference fails Quarkus startup`() {
        fail<Unit>("The test method must not run when startup fails as expected")
    }
}

@MuseConfig
@ApplicationScoped
class InvalidFlowConfig : MuseConfigDefinition() {
    val flow = flow("invalid-flow") {
        flowRef("missing-flow") {}
    }
}
