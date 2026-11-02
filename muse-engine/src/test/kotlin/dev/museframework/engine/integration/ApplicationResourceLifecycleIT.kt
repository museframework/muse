package dev.museframework.engine.integration

import dev.museframework.dsl.api.model.config.MuseConfiguration
import dev.museframework.dsl.api.model.flow.FlowInitialState
import dev.museframework.dsl.dsl.flow
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.source.MuseEventEnvelope
import dev.museframework.engine.api.source.MuseSource
import dev.museframework.engine.api.spi.ApplicationStartableResource
import dev.museframework.engine.app.MuseApplicationConfig
import dev.museframework.engine.app.bootstrap
import dev.museframework.engine.factory.MuseComponentFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class ApplicationResourceLifecycleIT {
    @Test
    fun `startable application resource starts with the app and closes on stop`() = runTest {
        val calls = mutableListOf<String>()
        val factory = MuseComponentFactory().apply {
            registerConfig(ResourceConfig::class) { _, context ->
                context.resources.register(object : ApplicationStartableResource {
                    override fun start() { calls += "start" }
                    override fun close() { calls += "close" }
                })
            }
        }
        val app = bootstrap(MuseApplicationConfig(emptyList(), listOf(ResourceConfig("resource")), factory))

        assertEquals(emptyList(), calls)
        app.init()
        assertEquals(emptyList(), calls)
        app.start()
        assertEquals(listOf("start"), calls)
        app.stop()
        assertEquals(listOf("start", "close"), calls)
    }

    @Test
    fun `failed startable resource closes during start failure`() = runTest {
        val calls = mutableListOf<String>()
        val factory = MuseComponentFactory().apply {
            registerConfig(ResourceConfig::class) { _, context ->
                context.resources.register(object : ApplicationStartableResource {
                    override fun start() { calls += "start"; throw IllegalStateException("resource start failed") }
                    override fun close() { calls += "close" }
                })
            }
        }
        val app = bootstrap(MuseApplicationConfig(emptyList(), listOf(ResourceConfig("resource")), factory))
        app.init()

        val failure = assertFailsWith<IllegalStateException> { app.start() }

        assertEquals("resource start failed", failure.message)
        assertEquals(listOf("start", "close"), calls)
    }

    @Test
    fun `stop before start closes an application resource exactly once`() =
        runTest {
            val closes = AtomicInteger()
            val factory = factory(closes)
            val app = bootstrap(MuseApplicationConfig(flows = emptyList(), configs = listOf(ResourceConfig("resource")), factory = factory))

            app.stop()
            app.stop()

            assertEquals(1, closes.get())
        }

    @Test
    fun `bootstrap failure closes resources registered by earlier configurations`() {
        val closes = AtomicInteger()
        val factory = factory(closes)
        factory.registerConfig(FailingConfig::class) { _, _ -> throw IllegalStateException("later config failed") }

        val failure =
            assertFailsWith<IllegalStateException> {
                bootstrap(
                    MuseApplicationConfig(
                        flows = emptyList(),
                        configs = listOf(ResourceConfig("resource"), FailingConfig("failing")),
                        factory = factory,
                    ),
                )
            }

        assertEquals("later config failed", failure.message)
        assertEquals(1, closes.get())
    }

    @Test
    fun `init failure closes resources registered during bootstrap`() =
        runTest {
            val closes = AtomicInteger()
            val factory = factory(closes)
            factory.registerProcessor(CaptureConfig::class) { _, _ ->
                throw IllegalStateException("processor initialization failed")
            }
            val failingFlow =
                flow("resource-init-failure") {
                    processor(CaptureConfig(docName = "failing processor", docId = "failing-processor"))
                }
            val app =
                bootstrap(
                    MuseApplicationConfig(
                        flows = listOf(failingFlow),
                        configs = listOf(ResourceConfig("resource")),
                        factory = factory,
                    ),
                )

            val failure = assertFailsWith<IllegalStateException> { app.init() }

            assertEquals("processor initialization failed", failure.message)
            assertEquals(1, closes.get())
        }

    @Test
    fun `a failed close does not prevent later resources from closing`() =
        runTest {
            val order = mutableListOf<String>()
            val factory = MuseComponentFactory()
            factory.registerConfig(ResourceConfig::class) { _, context ->
                context.resources.register(AutoCloseable { order += "first" })
                context.resources.register(
                    AutoCloseable {
                        order += "second"
                        throw IllegalStateException("close failed")
                    },
                )
            }
            val app = bootstrap(MuseApplicationConfig(emptyList(), listOf(ResourceConfig("resource")), factory))

            app.stop()

            assertEquals(listOf("second", "first"), order)
        }

    @Test
    fun `source start failure closes application resources`() =
        runTest {
            val closes = AtomicInteger()
            val factory = factory(closes)
            factory.registerSource(HarnessSourceConfig::class) { config, _, _ ->
                object : MuseSource {
                    override val docName = config.docName
                    override val docId = config.docId
                    override val docDescription = config.docDescription
                    override val sourceFile = config.sourceFile
                    override val sourceLine = config.sourceLine
                    override val sourceClass = config.sourceClass

                    override suspend fun start(channel: SendChannel<MuseEventEnvelope>) {
                        throw IllegalStateException("source start failed")
                    }

                    override suspend fun stop() = Unit

                    override suspend fun onResult(event: MuseEvent, transactionContext: Any?) = Unit
                }
            }
            val activeFlow =
                flow("resource-start-failure") {}.copy(
                    source = HarnessSourceConfig("failing source", "failing-source"),
                    initialState = FlowInitialState.STARTED,
                )
            val app =
                bootstrap(
                    MuseApplicationConfig(listOf(activeFlow), listOf(ResourceConfig("resource")), factory),
                    StandardTestDispatcher(testScheduler),
                )

            app.init()
            app.start()
            runCurrent()

            assertEquals(1, closes.get())
        }

    @Test
    fun `application resources close only after an accepted request drains`() =
        runTest {
            val closes = AtomicInteger()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val factory = factory(closes)
            factory.registerProcessor(CaptureConfig::class) { config, _ ->
                CaptureMuseProcessor(config) { context ->
                    entered.complete(Unit)
                    release.await()
                    ProcessorResult.Success(context)
                }
            }
            factory.registerSource(HarnessSourceConfig::class) { config, _, _ -> HarnessMuseSource(config) }
            val activeFlow =
                flow("resource-drain") {
                    processor(CaptureConfig(docName = "blocking processor", docId = "blocking-processor"))
                }.copy(
                    source = HarnessSourceConfig("request source", "request-source"),
                    initialState = FlowInitialState.STARTED,
                )
            val app =
                bootstrap(
                    MuseApplicationConfig(listOf(activeFlow), listOf(ResourceConfig("resource")), factory),
                    StandardTestDispatcher(testScheduler),
                )
            app.init()
            app.start()
            app.channelFor(activeFlow.name).send(MuseEventEnvelope(event = MuseEvent()))
            runCurrent()
            entered.await()

            val stopping = async { app.stop() }
            runCurrent()
            assertEquals(0, closes.get())

            release.complete(Unit)
            runCurrent()
            stopping.await()
            assertEquals(1, closes.get())
        }

    @Test
    fun `cancelling a stop caller cannot strand application resources`() =
        runTest {
            val closes = AtomicInteger()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val factory = factory(closes)
            factory.registerProcessor(CaptureConfig::class) { config, _ ->
                CaptureMuseProcessor(config) { context ->
                    entered.complete(Unit)
                    release.await()
                    ProcessorResult.Success(context)
                }
            }
            factory.registerSource(HarnessSourceConfig::class) { config, _, _ -> HarnessMuseSource(config) }
            val activeFlow =
                flow("resource-cancelled-stop") {
                    processor(CaptureConfig(docName = "blocking processor", docId = "blocking-processor"))
                }.copy(
                    source = HarnessSourceConfig("request source", "request-source"),
                    initialState = FlowInitialState.STARTED,
                )
            val app =
                bootstrap(
                    MuseApplicationConfig(listOf(activeFlow), listOf(ResourceConfig("resource")), factory),
                    StandardTestDispatcher(testScheduler),
                )
            app.init()
            app.start()
            app.channelFor(activeFlow.name).send(MuseEventEnvelope(event = MuseEvent()))
            runCurrent()
            entered.await()

            val stopping = async { app.stop() }
            runCurrent()
            stopping.cancel()
            release.complete(Unit)
            runCurrent()
            stopping.join()

            assertEquals(1, closes.get())
        }

    private fun factory(closes: AtomicInteger): MuseComponentFactory =
        MuseComponentFactory().apply {
            registerConfig(ResourceConfig::class) { _, context ->
                context.resources.register(AutoCloseable { closes.incrementAndGet() })
            }
        }

    private data class ResourceConfig(
        override val docName: String,
        override val docId: String = docName,
        override val docDescription: String? = null,
        override val sourceFile: String? = null,
        override val sourceLine: Int? = null,
        override val sourceClass: String? = null,
    ) : MuseConfiguration

    private data class FailingConfig(
        override val docName: String,
        override val docId: String = docName,
        override val docDescription: String? = null,
        override val sourceFile: String? = null,
        override val sourceLine: Int? = null,
        override val sourceClass: String? = null,
    ) : MuseConfiguration
}
