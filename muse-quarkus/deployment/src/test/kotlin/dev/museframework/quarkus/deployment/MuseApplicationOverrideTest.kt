package dev.museframework.quarkus.deployment

import dev.museframework.dsl.annotation.MuseConfig
import dev.museframework.dsl.api.config.MuseConfigDefinition
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.dsl.dsl.flow
import dev.museframework.engine.app.MuseApplication
import dev.museframework.engine.app.MuseApplicationConfig
import dev.museframework.engine.app.bootstrap
import dev.museframework.engine.factory.MuseComponentFactory
import io.quarkus.test.QuarkusUnitTest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.jboss.shrinkwrap.api.ShrinkWrap
import org.jboss.shrinkwrap.api.spec.JavaArchive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class MuseApplicationOverrideTest {
    companion object {
        @JvmField
        @RegisterExtension
        val unitTest: QuarkusUnitTest =
            QuarkusUnitTest().setArchiveProducer {
                ShrinkWrap.create(JavaArchive::class.java)
                    .addClasses(
                        ApplicationOverrideProducer::class.java,
                        IgnoredReflectiveConfig::class.java,
                    )
            }
    }

    @Inject
    lateinit var application: MuseApplication

    @Test
    fun `application bean overrides the default producer`() {
        assertEquals(setOf("override-flow"), application.context.flows.keys)
        assertFalse(application.context.flows.containsKey("ignored-default-flow"))
    }
}

@Singleton
class ApplicationOverrideProducer {
    @Produces
    @Singleton
    fun application(factory: MuseComponentFactory): MuseApplication =
        bootstrap(
            MuseApplicationConfig(
                flows = listOf(MuseFlow.Flow(name = "override-flow", docId = "override-flow-id")),
                factory = factory,
            ),
        )
}

@MuseConfig
@ApplicationScoped
class IgnoredReflectiveConfig : MuseConfigDefinition() {
    val flow = flow("ignored-default-flow") {}
}
