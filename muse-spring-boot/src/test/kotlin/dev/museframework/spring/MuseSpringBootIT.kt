package dev.museframework.spring

import dev.museframework.dsl.api.config.CustomCodeConfig
import dev.museframework.dsl.api.config.LoggerConfig
import dev.museframework.dsl.api.config.MuseConfigDefinition
import dev.museframework.dsl.api.config.SchedulerConfig
import dev.museframework.dsl.annotation.MuseConfig
import dev.museframework.dsl.dsl.customCode
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.scheduler
import dev.museframework.engine.api.spi.MuseComponentFactory
import dev.museframework.engine.api.spi.MuseComponentRegistrar
import dev.museframework.engine.app.MuseApplication
import dev.museframework.engine.components.processor.customCodeProcessorBuilder
import dev.museframework.engine.components.processor.dsl.logger
import dev.museframework.engine.components.processor.loggerProcessorBuilder
import dev.museframework.engine.components.source.schedulerSourceBuilder
import dev.museframework.springboot.MuseAutoConfiguration
import dev.museframework.springboot.MuseConfigProvider
import dev.museframework.springboot.MuseLifecycle
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.stereotype.Component
import org.springframework.test.annotation.DirtiesContext
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@SpringBootTest(
    classes = [
        MuseAutoConfiguration::class,
        MuseSpringBootIT.TestConfigDefinition::class,
        MuseSpringBootIT.TestConfigProvider::class,
        MuseSpringBootIT.TestComponentRegistrar::class,
    ],
)
class MuseSpringBootIT {
    companion object {
        private val processedFlows = LinkedBlockingQueue<String>()
    }

    @Autowired
    lateinit var museApplication: MuseApplication

    @Autowired
    lateinit var museLifecycle: MuseLifecycle

    // Declarative configuration

    @MuseConfig
    @Component
    class TestConfigDefinition : MuseConfigDefinition() {
        val declarativeFlow =
            flow("declarative-flow") {
                scheduler {
                    docName = "Scheduler"
                    fixedFrequency {
                        frequency = "100"
                    }
                }
                logger {
                    docName = "Logger"
                    message = "declarative: #[payload]"
                }
                customCode {
                    docName = "Record execution"
                    code { payload ->
                        processedFlows.offer("declarative-flow")
                        payload
                    }
                }
            }
    }

    // Explicit configuration

    @MuseConfig
    @Component
    class TestConfigProvider : MuseConfigProvider {
        override fun flows() =
            listOf(
                flow("explicit-flow") {
                    scheduler {
                        docName = "Scheduler"
                        fixedFrequency {
                            frequency = "100"
                        }
                    }
                    logger {
                        docName = "Logger"
                        message = "explicit: #[payload]"
                    }
                    customCode {
                        docName = "Record execution"
                        code { payload ->
                            processedFlows.offer("explicit-flow")
                            payload
                        }
                    }
                },
            )
    }

    // Custom registrar

    @Component
    class TestComponentRegistrar : MuseComponentRegistrar {
        override fun register(factory: MuseComponentFactory) {
            factory.registerSource(SchedulerConfig::class, schedulerSourceBuilder)
            factory.registerProcessor(LoggerConfig::class, loggerProcessorBuilder)
            factory.registerProcessor(CustomCodeConfig::class, customCodeProcessorBuilder)
        }
    }

    @Test
    fun `should create MuseApplication`() {
        assertNotNull(museApplication)
    }

    @Test
    fun `should register flows from MuseConfigDefinition`() {
        assertTrue(museApplication.context.flows.containsKey("declarative-flow"))
    }

    @Test
    fun `should register flows from MuseConfigProvider`() {
        assertTrue(museApplication.context.flows.containsKey("explicit-flow"))
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    fun `should start and stop via lifecycle`() {
        assertTrue(museLifecycle.isRunning)

        museLifecycle.stop()

        assertFalse(museLifecycle.isRunning)
    }

    @Test
    fun `should process scheduler events through both configuration styles`() {
        processedFlows.clear()
        val observed = mutableSetOf<String>()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)

        while (observed.size < 2) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) break

            processedFlows.poll(remaining, TimeUnit.NANOSECONDS)?.let(observed::add)
        }

        assertEquals(setOf("declarative-flow", "explicit-flow"), observed)
    }
}
