package dev.museframework.quarkus.deployment

import dev.museframework.dsl.api.model.component.Source
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.source.MuseEventEnvelope
import dev.museframework.engine.api.source.MuseSource
import dev.museframework.engine.api.spi.MuseComponentFactory
import dev.museframework.engine.api.spi.MuseComponentRegistrar
import dev.museframework.quarkus.MuseConfigProvider
import io.quarkus.test.QuarkusUnitTest
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.channels.SendChannel
import org.jboss.shrinkwrap.api.ShrinkWrap
import org.jboss.shrinkwrap.api.spec.JavaArchive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Duration

class MuseLifecycleTest {
    companion object {
        @JvmField
        @RegisterExtension
        val unitTest: QuarkusUnitTest =
            QuarkusUnitTest()
                .setArchiveProducer {
                    ShrinkWrap.create(JavaArchive::class.java)
                        .addClasses(
                            LifecycleConfigProvider::class.java,
                            LifecycleComponentRegistrar::class.java,
                            LifecycleSourceConfig::class.java,
                            LifecycleSource::class.java,
                            LifecycleProbe::class.java,
                        )
                }.setBeforeAllCustomizer { LifecycleProbe.reset() }
                .setAfterUndeployListener {
                    assertEquals(1, LifecycleProbe.count(LifecycleProbe.STOPPED))
                    LifecycleProbe.reset()
                }
    }

    @Test
    fun `startup initializes and starts each source once`() {
        awaitCount(LifecycleProbe.STARTED, 1)
        assertEquals(1, LifecycleProbe.count(LifecycleProbe.CREATED))
        assertEquals(1, LifecycleProbe.count(LifecycleProbe.STARTED))
        assertEquals(0, LifecycleProbe.count(LifecycleProbe.STOPPED))
    }

    private fun awaitCount(key: String, expected: Int) {
        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            while (LifecycleProbe.count(key) != expected) {
                Thread.onSpinWait()
            }
        }
    }
}

@ApplicationScoped
class LifecycleConfigProvider : MuseConfigProvider {
    override fun flows(): List<MuseFlow> =
        listOf(
            MuseFlow.Flow(
                name = "lifecycle-flow",
                docId = "lifecycle-flow-id",
                source = LifecycleSourceConfig(),
            ),
        )
}

@ApplicationScoped
class LifecycleComponentRegistrar : MuseComponentRegistrar {
    override fun register(factory: MuseComponentFactory) {
        factory.registerSource(LifecycleSourceConfig::class) { config, _, _ -> LifecycleSource(config) }
    }
}

data class LifecycleSourceConfig(
    override val docName: String = "Lifecycle Source",
    override val docId: String = "lifecycle-source-id",
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
) : Source

class LifecycleSource(
    config: LifecycleSourceConfig,
) : MuseSource {
    override val docName: String = config.docName
    override val docId: String = config.docId
    override val docDescription: String? = config.docDescription
    override val sourceFile: String? = config.sourceFile
    override val sourceLine: Int? = config.sourceLine
    override val sourceClass: String? = config.sourceClass

    init {
        LifecycleProbe.increment(LifecycleProbe.CREATED)
    }

    override suspend fun start(channel: SendChannel<MuseEventEnvelope>) {
        LifecycleProbe.increment(LifecycleProbe.STARTED)
    }

    override suspend fun stop() {
        LifecycleProbe.increment(LifecycleProbe.STOPPED)
    }

    override suspend fun onResult(
        event: MuseEvent,
        transactionContext: Any?,
    ) = Unit
}

object LifecycleProbe {
    const val CREATED = "muse.quarkus.test.lifecycle.created"
    const val STARTED = "muse.quarkus.test.lifecycle.started"
    const val STOPPED = "muse.quarkus.test.lifecycle.stopped"

    fun increment(key: String) {
        synchronized(System.getProperties()) {
            System.setProperty(key, (count(key) + 1).toString())
        }
    }

    fun count(key: String): Int = System.getProperty(key)?.toIntOrNull() ?: 0

    fun reset() {
        System.clearProperty(CREATED)
        System.clearProperty(STARTED)
        System.clearProperty(STOPPED)
    }
}
