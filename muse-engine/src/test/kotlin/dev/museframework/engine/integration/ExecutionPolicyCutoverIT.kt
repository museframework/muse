package dev.museframework.engine.integration

import dev.museframework.dsl.api.config.*
import dev.museframework.dsl.api.model.component.Router
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.dsl.api.model.flow.FlowRef
import dev.museframework.dsl.api.model.flow.FlowRefConfig
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.untilSuccessful
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.factory.MuseComponentFactory
import dev.museframework.engine.factory.MuseEngineRegistrar
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Observes the cutover contracts of the standard declarations through the production registrar
 * and the real bootstrap, never through a partially initialized context.
 */
class ExecutionPolicyCutoverIT {
    @Test
    fun `an enclosing ANY handler never consumes intermediate retry failures`() =
        runTest {
            var attempts = 0
            val handlerRuns = mutableListOf<String>()

            val flow =
                flow("retry-under-enclosing-any-handler") {
                    untilSuccessful {
                        docName = "Until Successful"
                        maxRetries = 3
                        millisBetweenRetries = 1
                        processor(CaptureConfig(docName = "Flaky", docId = "flaky-processor"))
                    }
                    errorHandler {
                        onErrorContinue(ErrorType.Mule.ANY) {
                            add(
                                CaptureConfig(
                                    docName = "Enclosing ANY handler",
                                    docId = "enclosing-any-handler",
                                ),
                            )
                        }
                    }
                }

            val factory =
                productionFactory { context ->
                    when (context.currentComponent?.docName) {
                        "Flaky" -> {
                            attempts += 1
                            if (attempts <= 2) {
                                failure(context, "flaky attempt $attempts")
                            } else {
                                ProcessorResult.Success(
                                    context.withEvent(
                                        context.event.copy(
                                            payload = TypedValue.of("eventual-success"),
                                        ),
                                    ),
                                )
                            }
                        }

                        else -> {
                            handlerRuns += context.currentComponent?.docName.orEmpty()
                            ProcessorResult.Success(
                                context.withEvent(
                                    context.event.copy(payload = TypedValue.of("handler-ran")),
                                ),
                            )
                        }
                    }
                }

            val result = EngineFlowHarness(this, flow, factory).execute()

            assertEquals(
                3,
                attempts,
                "untilSuccessful must retry the two initial failures " +
                    "(payload=${result.payload?.value}, handlerRuns=$handlerRuns)",
            )
            assertEquals("eventual-success", result.payload?.value)
            assertEquals(emptyList(), handlerRuns, "no enclosing handler may observe an intermediate retry failure")
            assertNull(result.error)
        }

    @Test
    fun `migration negative - every standard router is a managed declaration`() {
        val factory = MuseComponentFactory().also { MuseEngineRegistrar().register(it) }

        standardRouterConfigs().forEach { component ->
            assertNotNull(
                factory.policyDeclaration(component),
                "${component::class.simpleName} must be registered as a managed declaration",
            )
        }
    }

    @Test
    fun `migration negative - processor results expose only terminal success and failure`() {
        assertEquals(
            setOf("Success", "Failure"),
            ProcessorResult::class.sealedSubclasses.map { it.simpleName }.toSet(),
        )
    }

    private fun standardRouterConfigs(): List<Router> =
        listOf(
            FlowRefConfig("flow-ref", "flow-ref", ref = FlowRef.ByName("referenced")),
            ChoiceConfig("choice", "choice", whenRoutes = emptyList(), otherwise = null),
            TryConfig("try", "try", pipeline = emptyList(), errorHandler = null),
            ForEachConfig("for-each", "for-each", collection = null, pipeline = emptyList()),
            AsyncConfig("async", "async", pipeline = emptyList()),
            ScatterGatherConfig("scatter-gather", "scatter-gather", routes = emptyList()),
            UntilSuccessfulConfig("until-successful", "until-successful", maxRetries = 1, millisBetweenRetries = 0, pipeline = emptyList()),
            ParallelForEachConfig("parallel-for-each", "parallel-for-each", collection = null, pipeline = emptyList()),
            WithCorrelationIdConfig("with-correlation-id", "with-correlation-id", correlationId = "correlation", pipeline = emptyList()),
        )

    private fun productionFactory(
        capture: suspend (ExecutionContext) -> ProcessorResult,
    ): MuseComponentFactory =
        MuseComponentFactory().apply {
            MuseEngineRegistrar().register(this)
            registerProcessor(CaptureConfig::class) { component, _ ->
                CaptureMuseProcessor(component, capture)
            }
        }

    private fun failure(
        context: ExecutionContext,
        description: String,
    ): ProcessorResult.Failure =
        ProcessorResult.Failure(
            error =
                MuseError(
                    description = description,
                    detailedDescription = description,
                    errorType = ErrorType.Mule.CONNECTIVITY,
                    cause = RuntimeException(description),
                ),
            context = context,
        )
}
