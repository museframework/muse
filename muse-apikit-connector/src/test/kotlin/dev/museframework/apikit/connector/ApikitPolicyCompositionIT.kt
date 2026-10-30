package dev.museframework.apikit.connector

import dev.museframework.apikit.api.config.ApikitConfig
import dev.museframework.apikit.api.config.ApikitRef
import dev.museframework.apikit.api.config.ApikitRouter
import dev.museframework.apikit.api.error.ApikitErrorType
import dev.museframework.dsl.api.config.RaiseErrorConfig
import dev.museframework.dsl.api.model.component.Source
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.dsl.dsl.flow
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.source.MuseEventEnvelope
import dev.museframework.engine.api.source.MuseSource
import dev.museframework.engine.app.MuseApplicationConfig
import dev.museframework.engine.app.bootstrap
import dev.museframework.engine.factory.MuseComponentFactory
import dev.museframework.engine.factory.MuseEngineRegistrar
import dev.museframework.http.api.model.HttpAttributes
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.test.policy.DefaultOnFailureConfig
import dev.museframework.test.policy.DefaultOnFailureRegistrar
import dev.museframework.test.policy.defaultOnFailure
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Composition evidence for the APIKit declaration migration: routed flows are prepared where they
 * are declared, so a managed external policy inside a routed body — and inside its error handler —
 * initializes and executes through a real application bootstrap.
 */
class ApikitPolicyCompositionIT {
    @Test
    fun `a routed body containing an external policy initializes and executes`() =
        withApplication(routedFlow(raiseType = CONNECTIVITY)) { application ->
            val reply = application.execute("/users")

            assertEquals("recovered-in-body", reply.payload?.value)
            assertEquals(null, reply.error)
        }

    @Test
    fun `a routed error handler containing an external policy initializes and executes`() =
        withApplication(
            routedFlow(
                raiseType = CONNECTIVITY,
                bodyEligibleErrorTypes = setOf(ErrorType.Mule.TIMEOUT),
                handler = {
                    onErrorContinue(ErrorType.Mule.ANY) {
                        add(
                            DefaultOnFailureConfig(
                                body = listOf(raiseError(CONNECTIVITY, "handler failure")),
                                eligibleErrorTypes = setOf(ErrorType.Mule.CONNECTIVITY),
                                replacement = TypedValue.of("recovered-in-handler"),
                                docName = "Handler Default On Failure",
                                docId = "handler-recovery",
                            ),
                        )
                    }
                },
            ),
        ) { application ->
            val reply = application.execute("/users")

            assertEquals(
                "recovered-in-handler",
                reply.payload?.value,
                "diagnostic error=${reply.error?.errorType} description=${reply.error?.description}",
            )
            assertEquals(null, reply.error)
        }

    @Test
    fun `a matched route without a flow keeps its missing-route semantics`() =
        withApplication(routedFlow(raiseType = CONNECTIVITY, name = NOT_ROUTED_FLOW)) { application ->
            val reply = application.execute("/users")

            assertEquals(ApikitErrorType.FLOW_NOT_FOUND, reply.error?.errorType)
        }

    private fun withApplication(
        routed: dev.museframework.dsl.api.model.flow.MuseFlow.Flow,
        block: (RunningApplication) -> Unit,
    ) {
        val factory =
            MuseComponentFactory().apply {
                MuseEngineRegistrar().register(this)
                ApikitConnectorRegistrar().register(this)
                DefaultOnFailureRegistrar().register(this)
                registerSource(CompositionSourceConfig::class) { component, _, _ -> CompositionSource(component) }
            }
        val apiFlow =
            flow(API_FLOW) {
                router(ApikitRouter(configRef = ApikitRef.ByName(CONFIG_NAME)))
            }
        val application =
            bootstrap(
                MuseApplicationConfig(
                    flows = listOf(withSource(apiFlow), routed),
                    configs = listOf(ApikitConfig(name = CONFIG_NAME, spec = SPEC_PATH)),
                    factory = factory,
                ),
            )
        runBlocking {
            application.init()
            try {
                block(RunningApplication(application))
            } finally {
                application.stop()
            }
        }
    }

    private fun routedFlow(
        raiseType: String,
        bodyEligibleErrorTypes: Set<ErrorType> = setOf(ErrorType.Mule.CONNECTIVITY),
        handler: (dev.museframework.dsl.builder.ErrorHandlerStrategyBuilder.() -> Unit)? = null,
        name: String = ROUTED_FLOW,
    ) = flow(name) {
        defaultOnFailure(
            DefaultOnFailureConfig(
                body = listOf(raiseError(raiseType, "routed failure")),
                eligibleErrorTypes = bodyEligibleErrorTypes,
                replacement = TypedValue.of("recovered-in-body"),
                docName = "Default On Failure",
                docId = "body-recovery",
            ),
        )
        handler?.let { configure -> errorHandler(configure) }
    }

    private fun raiseError(
        type: String,
        description: String,
    ) = RaiseErrorConfig(
        docName = "Raise Error",
        docId = "raise-$type",
        type = type,
        description = description,
    )

    private fun withSource(
        flow: dev.museframework.dsl.api.model.flow.MuseFlow.Flow,
    ) = flow.copy(source = CompositionSourceConfig(docName = "${flow.name} source", docId = "${flow.docId}-source"))

    private class RunningApplication(
        val application: dev.museframework.engine.app.MuseApplication,
    ) {
        fun execute(path: String): MuseEvent =
            runBlocking {
                val envelope =
                    MuseEventEnvelope(
                        event =
                            MuseEvent(
                                attributes =
                                    TypedValue.of(
                                        HttpAttributes(
                                            method = HttpMethod.GET,
                                            requestPath = path,
                                            queryParams = emptyMap(),
                                            headers = emptyMap(),
                                            requestUri = path,
                                            remoteAddress = "127.0.0.1",
                                        ),
                                    ),
                            ),
                    )
                application.channelFor(API_FLOW).send(envelope)
                withTimeout(REQUEST_TIMEOUT_MILLIS) { envelope.replyTo.await() }
            }
    }

    private companion object {
        const val CONFIG_NAME = "my-api"
        const val SPEC_PATH = "api/valid.raml"
        const val API_FLOW = "api"
        const val ROUTED_FLOW = "get:\\users:my-api"
        const val NOT_ROUTED_FLOW = "not-the-routed-flow"
        const val CONNECTIVITY = "MULE:CONNECTIVITY"
        const val REQUEST_TIMEOUT_MILLIS = 5_000L
    }
}

private data class CompositionSourceConfig(
    override val docName: String,
    override val docId: String,
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
) : Source

private class CompositionSource(
    config: CompositionSourceConfig,
) : MuseSource {
    override val docName: String = config.docName
    override val docId: String = config.docId
    override val docDescription: String? = config.docDescription
    override val sourceFile: String? = config.sourceFile
    override val sourceLine: Int? = config.sourceLine
    override val sourceClass: String? = config.sourceClass

    override suspend fun start(channel: SendChannel<MuseEventEnvelope>) = Unit

    override suspend fun stop() = Unit

    override suspend fun onResult(
        event: MuseEvent,
        transactionContext: Any?,
    ) = Unit
}
