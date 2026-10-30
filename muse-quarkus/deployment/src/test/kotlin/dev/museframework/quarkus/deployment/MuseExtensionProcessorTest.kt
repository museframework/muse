package dev.museframework.quarkus.deployment

import dev.museframework.dsl.annotation.MuseConfig
import dev.museframework.dsl.api.config.MuseConfigDefinition
import dev.museframework.dsl.api.model.config.Configuration
import dev.museframework.dsl.api.model.config.MuseConfiguration
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.dsl.dsl.flow
import dev.museframework.engine.app.MuseApplication
import dev.museframework.quarkus.MuseConfigProvider
import io.quarkus.test.QuarkusUnitTest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.shrinkwrap.api.ShrinkWrap
import org.jboss.shrinkwrap.api.spec.JavaArchive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class MuseExtensionProcessorTest {
    companion object {
        @JvmField
        @RegisterExtension
        val unitTest: QuarkusUnitTest =
            QuarkusUnitTest().setArchiveProducer {
                ShrinkWrap.create(JavaArchive::class.java)
                    .addClasses(
                        FirstReflectiveConfig::class.java,
                        SecondReflectiveConfig::class.java,
                        ExplicitConfigProvider::class.java,
                    )
            }
    }

    @Inject
    lateinit var application: MuseApplication

    @Inject
    lateinit var provider: ExplicitConfigProvider

    @Test
    fun `discovers flows and configurations from reflective definitions`() {
        assertTrue(application.context.flows.containsKey("reflective-flow-one"))
        assertTrue(application.context.flows.containsKey("reflective-flow-two"))
        assertTrue(application.context.configs.containsKey("reflective-config"))
    }

    @Test
    fun `loads flows and configurations from explicit provider`() {
        assertTrue(application.context.flows.containsKey("provider-flow"))
        assertTrue(application.context.configs.containsKey("provider-config"))
    }

    @Test
    fun `merges every definition and provider exactly once`() {
        assertEquals(
            setOf("reflective-flow-one", "reflective-flow-two", "provider-flow"),
            application.context.flows.keys,
        )
        assertEquals(
            setOf("reflective-config", "provider-config"),
            application.context.configs.keys,
        )
        assertEquals(1, provider.flowCalls)
        assertEquals(1, provider.configCalls)
    }
}

@MuseConfig
@ApplicationScoped
class FirstReflectiveConfig : MuseConfigDefinition() {
    val flow = flow("reflective-flow-one") {}
    val config = Configuration(docName = "reflective-config")
}

@MuseConfig
@ApplicationScoped
class SecondReflectiveConfig : MuseConfigDefinition() {
    val flow = flow("reflective-flow-two") {}
}

@ApplicationScoped
class ExplicitConfigProvider : MuseConfigProvider {
    var flowCalls: Int = 0
        private set
    var configCalls: Int = 0
        private set

    override fun flows(): List<MuseFlow> {
        flowCalls++
        return listOf(MuseFlow.Flow(name = "provider-flow", docId = "provider-flow-id"))
    }

    override fun configs(): List<MuseConfiguration> {
        configCalls++
        return listOf(Configuration(docName = "provider-config"))
    }
}
