package dev.museframework.engine.integration

import dev.museframework.dsl.api.config.AsyncConfig
import dev.museframework.dsl.api.config.RaiseErrorConfig
import dev.museframework.dsl.api.config.SchedulerConfig
import dev.museframework.dsl.api.config.SetPayloadConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.dsl.dsl.async
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.raiseError
import dev.museframework.dsl.dsl.scheduler
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.app.MuseApplicationConfig
import dev.museframework.engine.app.bootstrap
import dev.museframework.engine.components.processor.raiseErrorProcessorBuilder
import dev.museframework.engine.components.processor.setPayloadProcessorBuilder
import dev.museframework.engine.execution.policy.asyncPolicyDeclaration
import dev.museframework.engine.components.source.schedulerSourceBuilder
import dev.museframework.engine.factory.MuseComponentFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AsyncIT {
    private fun defaultFactory(): MuseComponentFactory =
        MuseComponentFactory().apply {
            registerSource(SchedulerConfig::class, schedulerSourceBuilder)
            registerProcessor(SetPayloadConfig::class, setPayloadProcessorBuilder)
            registerProcessor(RaiseErrorConfig::class, raiseErrorProcessorBuilder)
            registerPolicyRouter(AsyncConfig::class, asyncPolicyDeclaration)
        }

    @Test
    fun `should fire and forget - main flow continues immediately`() =
        runBlocking {
            val mainCompleted = CountDownLatch(1)
            val asyncStarted = CountDownLatch(1)
            val asyncCompleted = CountDownLatch(1)
            val releaseAsync = CompletableDeferred<Unit>()
            var capturedMainEvent: MuseEvent? = null
            val factory = defaultFactory()
            factory.registerProcessor(CaptureConfig::class) { component, _ ->
                CaptureMuseProcessor(component) { context ->
                    when (component.docId) {
                        "async-processor" -> {
                            asyncStarted.countDown()
                            releaseAsync.await()
                            asyncCompleted.countDown()
                        }

                        "main-capture" -> {
                            capturedMainEvent = context.event
                            mainCompleted.countDown()
                        }
                    }
                    ProcessorResult.Success(context)
                }
            }
            val flow =
                flow("async-fire-forget") {
                    scheduler {
                        docName = "Scheduler"
                        fixedFrequency { frequency = "60000" }
                    }
                    setPayload {
                        docName = "Main payload"
                        value = "main-value"
                    }
                    async {
                        docName = "Async block"
                        processor(CaptureConfig(docName = "Async processor", docId = "async-processor"))
                    }
                    processor(CaptureConfig(docName = "Main capture", docId = "main-capture"))
                }

            var asyncDidStart = false
            var mainFinishedBeforeAsyncRelease = false
            var asyncFinishedAfterRelease = false
            runStartedApplication(flow, factory) {
                asyncDidStart = asyncStarted.await(5, TimeUnit.SECONDS)
                mainFinishedBeforeAsyncRelease = mainCompleted.await(1, TimeUnit.SECONDS)
                releaseAsync.complete(Unit)
                asyncFinishedAfterRelease = asyncCompleted.await(5, TimeUnit.SECONDS)
            }

            assertTrue(asyncDidStart, "The async branch should start")
            assertTrue(
                mainFinishedBeforeAsyncRelease,
                "The main flow should finish while the async branch is suspended",
            )
            assertTrue(asyncFinishedAfterRelease, "The async branch should finish after it is released")
            assertNotNull(capturedMainEvent)
            assertEquals("main-value", capturedMainEvent?.payload?.value)
        }

    @Test
    fun `should not propagate async changes to main flow`() =
        runBlocking {
            val mainCompleted = CountDownLatch(1)
            val asyncCompleted = CountDownLatch(1)
            var capturedMainEvent: MuseEvent? = null
            var capturedAsyncEvent: MuseEvent? = null
            val factory = defaultFactory()
            factory.registerProcessor(CaptureConfig::class) { component, _ ->
                CaptureMuseProcessor(component) { context ->
                    when (component.docId) {
                        "async-capture" -> {
                            capturedAsyncEvent = context.event
                            asyncCompleted.countDown()
                        }

                        "main-capture" -> {
                            capturedMainEvent = context.event
                            mainCompleted.countDown()
                        }
                    }
                    ProcessorResult.Success(context)
                }
            }
            val flow =
                flow("async-no-propagation") {
                    scheduler {
                        docName = "Scheduler"
                        fixedFrequency { frequency = "60000" }
                    }
                    setPayload {
                        docName = "Main payload"
                        value = "original"
                    }
                    async {
                        docName = "Async block"
                        setPayload {
                            docName = "Async payload"
                            value = "async-modified"
                        }
                        processor(CaptureConfig(docName = "Async capture", docId = "async-capture"))
                    }
                    processor(CaptureConfig(docName = "Main capture", docId = "main-capture"))
                }

            var mainDidComplete = false
            var asyncDidComplete = false
            runStartedApplication(flow, factory) {
                mainDidComplete = mainCompleted.await(5, TimeUnit.SECONDS)
                asyncDidComplete = asyncCompleted.await(5, TimeUnit.SECONDS)
            }

            assertTrue(mainDidComplete)
            assertTrue(asyncDidComplete)
            assertEquals("original", capturedMainEvent?.payload?.value)
            assertEquals("async-modified", capturedAsyncEvent?.payload?.value)
        }

    @Test
    fun `maxConcurrency should limit concurrent async executions`() =
        runBlocking {
            val concurrentCount = AtomicInteger(0)
            val maxObservedConcurrency = AtomicInteger(0)
            val threeExecutionsCompleted = CountDownLatch(3)
            val factory = defaultFactory()
            factory.registerProcessor(CaptureConfig::class) { component, _ ->
                CaptureMuseProcessor(component) { context ->
                    if (component.docId == "async-slow") {
                        val current = concurrentCount.incrementAndGet()
                        maxObservedConcurrency.updateAndGet { maximum -> maxOf(maximum, current) }
                        try {
                            delay(200)
                            threeExecutionsCompleted.countDown()
                        } finally {
                            concurrentCount.decrementAndGet()
                        }
                    }
                    ProcessorResult.Success(context)
                }
            }
            val flow =
                flow("async-max-concurrency") {
                    scheduler {
                        docName = "Scheduler"
                        fixedFrequency { frequency = "50" }
                    }
                    async {
                        docName = "Async block"
                        maxConcurrency = 1
                        processor(CaptureConfig(docName = "Slow async processor", docId = "async-slow"))
                    }
                }

            var completed = false
            runStartedApplication(flow, factory) {
                completed = threeExecutionsCompleted.await(10, TimeUnit.SECONDS)
            }

            assertTrue(completed, "Three async executions should complete")
            assertEquals(
                1,
                maxObservedConcurrency.get(),
                "maxConcurrency=1 should allow only one concurrent async execution",
            )
        }

    @Test
    fun `error inside async should not be caught by flow-level error handler`() =
        runBlocking {
            val mainCompleted = CountDownLatch(1)
            val asyncStarted = CountDownLatch(1)
            val processorAfterError = CountDownLatch(1)
            val flowErrorHandlerInvoked = AtomicBoolean(false)
            var capturedMainEvent: MuseEvent? = null
            val factory = defaultFactory()
            factory.registerProcessor(CaptureConfig::class) { component, _ ->
                CaptureMuseProcessor(component) { context ->
                    when (component.docId) {
                        "main-capture" -> {
                            capturedMainEvent = context.event
                            mainCompleted.countDown()
                        }

                        "async-start" -> asyncStarted.countDown()
                        "flow-error-handler-capture" -> flowErrorHandlerInvoked.set(true)
                        "async-after-raise" -> processorAfterError.countDown()
                    }
                    ProcessorResult.Success(context)
                }
            }
            val flow =
                flow("async-error-isolation") {
                    scheduler {
                        docName = "Scheduler"
                        fixedFrequency { frequency = "60000" }
                    }
                    setPayload {
                        docName = "Main payload"
                        value = "main-ok"
                    }
                    async {
                        docName = "Async block"
                        processor(CaptureConfig(docName = "Async start", docId = "async-start"))
                        raiseError {
                            docName = "Raise inside async"
                            type = "APP:ASYNC_FAILURE"
                            description = "Error in async scope"
                        }
                        processor(CaptureConfig(docName = "After raise", docId = "async-after-raise"))
                    }
                    processor(CaptureConfig(docName = "Main capture", docId = "main-capture"))
                    errorHandler {
                        onErrorContinue(ErrorType.Mule.ANY) {
                            add(
                                CaptureConfig(
                                    docName = "Flow error handler capture",
                                    docId = "flow-error-handler-capture",
                                ),
                            )
                        }
                    }
                }

            var mainDidComplete = false
            var asyncDidStart = false
            var processorAfterErrorInvoked = false
            runStartedApplication(flow, factory) {
                mainDidComplete = mainCompleted.await(5, TimeUnit.SECONDS)
                asyncDidStart = asyncStarted.await(5, TimeUnit.SECONDS)
                processorAfterErrorInvoked = processorAfterError.await(500, TimeUnit.MILLISECONDS)
            }

            assertTrue(mainDidComplete, "The main flow should complete")
            assertTrue(asyncDidStart, "The async branch should execute")
            assertEquals("main-ok", capturedMainEvent?.payload?.value)
            assertFalse(
                flowErrorHandlerInvoked.get(),
                "The flow-level error handler must not receive errors from an async scope",
            )
            assertFalse(
                processorAfterErrorInvoked,
                "A processor after an unhandled async error must not execute",
            )
        }

    private suspend fun runStartedApplication(
        flow: MuseFlow.Flow,
        factory: MuseComponentFactory,
        block: suspend () -> Unit,
    ) {
        val application =
            bootstrap(
                MuseApplicationConfig(
                    flows = listOf(flow),
                    factory = factory,
                ),
            )
        try {
            application.init()
            application.start()
            block()
        } finally {
            application.stop()
        }
    }
}
