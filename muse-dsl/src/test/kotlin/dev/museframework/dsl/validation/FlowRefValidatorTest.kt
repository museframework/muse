package dev.museframework.dsl.validation

import dev.museframework.dsl.api.config.AsyncConfig
import dev.museframework.dsl.api.config.ChoiceConfig
import dev.museframework.dsl.api.config.ChoiceRoute
import dev.museframework.dsl.api.config.ForEachConfig
import dev.museframework.dsl.api.config.ParallelForEachConfig
import dev.museframework.dsl.api.config.ScatterGatherConfig
import dev.museframework.dsl.api.config.ScatterGatherRoute
import dev.museframework.dsl.api.config.TryConfig
import dev.museframework.dsl.api.config.UntilSuccessfulConfig
import dev.museframework.dsl.api.config.WithCorrelationIdConfig
import dev.museframework.dsl.api.model.error.ErrorHandler
import dev.museframework.dsl.api.model.error.ErrorHandlerStrategy
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.dsl.api.model.flow.FlowRef
import dev.museframework.dsl.api.model.flow.FlowRefConfig
import dev.museframework.dsl.api.model.flow.MuseFlow
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FlowRefValidatorTest {
    @Test
    fun `validates named flow references inside every nested router pipeline`() {
        val flow =
            MuseFlow.Flow(
                name = "nested-flow-refs",
                docId = "nested-flow-refs",
                pipeline =
                    listOf(
                        UntilSuccessfulConfig(
                            docName = "Until successful",
                            docId = "until-successful",
                            maxRetries = 1,
                            millisBetweenRetries = 1,
                            pipeline = listOf(missingRef("until-successful-ref")),
                        ),
                        ForEachConfig(
                            docName = "For each",
                            docId = "for-each",
                            collection = "#[[]]",
                            pipeline = listOf(missingRef("for-each-ref")),
                        ),
                        ParallelForEachConfig(
                            docName = "Parallel for each",
                            docId = "parallel-for-each",
                            collection = "#[[]]",
                            pipeline = listOf(missingRef("parallel-for-each-ref")),
                        ),
                        ScatterGatherConfig(
                            docName = "Scatter gather",
                            docId = "scatter-gather",
                            routes =
                                listOf(
                                    ScatterGatherRoute(
                                        pipeline = listOf(missingRef("scatter-gather-ref")),
                                    ),
                                ),
                        ),
                        ChoiceConfig(
                            docName = "Choice",
                            docId = "choice",
                            whenRoutes =
                                listOf(
                                    ChoiceRoute(
                                        expression = "#[true]",
                                        pipeline = listOf(missingRef("choice-when-ref")),
                                    ),
                                ),
                            otherwise = listOf(missingRef("choice-otherwise-ref")),
                        ),
                        WithCorrelationIdConfig(
                            docName = "With correlation id",
                            docId = "with-correlation-id",
                            correlationId = "correlation-id",
                            pipeline = listOf(missingRef("with-correlation-id-ref")),
                        ),
                        TryConfig(
                            docName = "Try",
                            docId = "try",
                            pipeline = listOf(missingRef("try-body-ref")),
                            errorHandler =
                                ErrorHandler(
                                    strategies =
                                        listOf(
                                            ErrorHandlerStrategy.OnErrorContinue(
                                                errorType = ErrorType.Mule.ANY,
                                                pipeline = listOf(missingRef("try-handler-ref")),
                                            ),
                                        ),
                                ),
                        ),
                        AsyncConfig(
                            docName = "Async",
                            docId = "async",
                            pipeline = listOf(missingRef("async-ref")),
                        ),
                    ),
            )

        val errors = validateFlowRefs(mapOf(flow.name to flow))

        assertEquals(
            setOf(
                "until-successful-ref",
                "for-each-ref",
                "parallel-for-each-ref",
                "scatter-gather-ref",
                "choice-when-ref",
                "choice-otherwise-ref",
                "with-correlation-id-ref",
                "try-body-ref",
                "try-handler-ref",
                "async-ref",
            ),
            errors.map { error -> (error.component as FlowRefConfig).docId }.toSet(),
        )
    }

    private fun missingRef(name: String): FlowRefConfig =
        FlowRefConfig(
            docName = name,
            docId = name,
            ref = FlowRef.ByName(name),
        )
}
