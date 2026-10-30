package dev.museframework.engine.integration

import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.scheduler
import dev.museframework.engine.app.MuseApplicationConfig
import dev.museframework.engine.app.MuseBootstrapException
import dev.museframework.engine.app.bootstrap
import dev.museframework.engine.factory.MuseComponentFactory
import dev.museframework.engine.factory.MuseEngineRegistrar
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class SchedulerIT {

    @Test
    fun `scheduler flow must contain at least one processor`() {
        val factory = MuseComponentFactory()
        MuseEngineRegistrar().register(factory)

        val flow =
            flow("empty-scheduler-flow") {
                scheduler {
                    fixedFrequency {
                        frequency = "100"
                    }
                }
            }

        val exception =
            assertThrows(MuseBootstrapException::class.java) {
                bootstrap(MuseApplicationConfig(flows = listOf(flow), factory = factory))
            }

        assertTrue(
            exception.errors.any {
                it.message.contains(
                    "Scheduler flow 'empty-scheduler-flow' must contain at least one processor or router",
                )
            },
        )
    }
}
