package dev.museframework.engine.integration

import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.dsl.dsl.flow
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.streaming.RepeatableFileStoreCursorStreamProvider
import dev.museframework.engine.api.streaming.StreamingProviderClosedException
import dev.museframework.engine.factory.MuseComponentFactory
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StreamingResourceLifecycleIT {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `engine releases a provider introduced by a successful processor`() =
        runTest {
            lateinit var provider: RepeatableFileStoreCursorStreamProvider
            val harness = harness("streaming-success-cleanup") { context ->
                provider = fileProvider("processor output")
                ProcessorResult.Success(
                    context.withEvent(
                        context.event.copy(payload = TypedValue.of(provider as Any)),
                    ),
                )
            }

            val result = harness.execute()

            assertSame(provider, result.payload?.value)
            assertReleased(provider)
            assertTempDirectoryEmpty()
        }

    @Test
    fun `engine transfers the response provider to the reply owner`() =
        runTest {
            lateinit var provider: RepeatableFileStoreCursorStreamProvider
            val harness = harness("streaming-reply-transfer") { context ->
                provider = fileProvider("transferred output")
                ProcessorResult.Success(
                    context.withEvent(
                        context.event.copy(payload = TypedValue.of(provider as Any)),
                    ),
                )
            }

            val execution =
                harness.executeWithContext(
                    claimReplyResources = { event ->
                        setOf(requireNotNull(event.payload?.asCursorStreamProvider()))
                    },
                )

            assertSame(provider, execution.event.payload?.value)
            assertFalse(provider.isClosed, "The exported reply claim keeps the provider alive")
            provider.openCursor().use { cursor ->
                assertEquals("transferred output", cursor.readBytes().decodeToString())
            }

            // The reply owner closes the retained claim once serialization is done.
            execution.replyLeases.forEach { it.close() }
            assertReleased(provider)
            assertTempDirectoryEmpty()
        }

    @Test
    fun `engine transfers an unchanged input provider without releasing it`() =
        runTest {
            val provider = fileProvider("pass through")
            val event = MuseEvent(payload = TypedValue.of(provider as Any))
            val harness = harness("streaming-pass-through") { context ->
                ProcessorResult.Success(context)
            }

            val execution =
                harness.executeWithContext(
                    event = event,
                    claimReplyResources = { reply ->
                        setOf(requireNotNull(reply.payload?.asCursorStreamProvider()))
                    },
                )

            assertSame(provider, execution.event.payload?.value)
            assertEquals("pass through", provider.openCursor().use { it.readBytes().decodeToString() })

            execution.replyLeases.forEach { it.close() }
            assertReleased(provider)
            assertTempDirectoryEmpty()
        }

    @Test
    fun `engine releases a provider returned on a processor failure`() =
        runTest {
            lateinit var provider: RepeatableFileStoreCursorStreamProvider
            val harness = harness("streaming-result-failure") { context ->
                provider = fileProvider("failed output")
                val failedContext =
                    context.withEvent(
                        context.event.copy(payload = TypedValue.of(provider as Any)),
                    )
                ProcessorResult.Failure(
                    error =
                        MuseError(
                            description = "streaming processor failed",
                            detailedDescription = "failure after provider creation",
                            errorType = ErrorType.Mule.CONNECTIVITY,
                            cause = IllegalStateException("test failure"),
                        ),
                    context = failedContext,
                )
            }

            val result = harness.execute()

            assertEquals(ErrorType.Mule.CONNECTIVITY, result.error?.errorType)
            assertReleased(provider)
            assertTempDirectoryEmpty()
        }

    @Test
    fun `engine releases explicitly registered resources when a processor throws`() =
        runTest {
            lateinit var provider: RepeatableFileStoreCursorStreamProvider
            val harness = harness("streaming-exception-cleanup") { context ->
                provider = fileProvider("exception output")
                context.streamingResources.register(provider)
                throw IllegalStateException("processor failed after provider creation")
            }

            val failure = assertFailsWith<IllegalStateException> { harness.execute() }

            assertEquals("processor failed after provider creation", failure.message)
            assertReleased(provider)
            assertTempDirectoryEmpty()
        }

    private fun TestScope.harness(
        name: String,
        process: suspend (ExecutionContext) -> ProcessorResult,
    ): EngineFlowHarness {
        val factory = MuseComponentFactory()
        factory.registerProcessor(CaptureConfig::class) { component, _ ->
            CaptureMuseProcessor(component, process)
        }
        val flow =
            flow(name) {
                processor(CaptureConfig(docName = "Streaming lifecycle", docId = "$name-processor"))
            }
        return EngineFlowHarness(this, flow, factory)
    }

    private fun fileProvider(content: String): RepeatableFileStoreCursorStreamProvider =
        RepeatableFileStoreCursorStreamProvider.fromBytes(
            bytes = content.toByteArray(),
            inMemorySizeBytes = 0,
            tempDirectory = tempDirectory,
        )

    private fun assertReleased(provider: RepeatableFileStoreCursorStreamProvider) {
        assertTrue(provider.isClosed)
        assertFailsWith<StreamingProviderClosedException> { provider.openCursor() }
    }

    private fun assertTempDirectoryEmpty() {
        Files.list(tempDirectory).use { files -> assertEquals(0, files.count()) }
    }
}
