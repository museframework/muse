package dev.museframework.engine.integration

import dev.museframework.dsl.api.model.component.Source
import dev.museframework.dsl.api.model.config.MuseConfiguration
import dev.museframework.dsl.api.model.flow.FlowInitialState
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.source.MuseEventEnvelope
import dev.museframework.engine.api.source.MuseSource
import dev.museframework.engine.api.streaming.MuseCursor
import dev.museframework.engine.api.streaming.MuseCursorProvider
import dev.museframework.engine.api.streaming.StreamingResourceLease
import dev.museframework.engine.api.streaming.StreamingResourceRegistry
import dev.museframework.engine.app.MuseApplicationConfig
import dev.museframework.engine.app.bootstrap
import dev.museframework.engine.factory.MuseComponentFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import java.util.Properties

/**
 * Executes a source-less flow through the real bootstrap and engine pipeline
 * without starting a timer or another external source.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineFlowHarness(
    private val testScope: TestScope,
    private val flow: MuseFlow.Flow,
    private val factory: MuseComponentFactory,
    private val configs: List<MuseConfiguration> = emptyList(),
    private val additionalFlows: List<MuseFlow> = emptyList(),
) {
    init {
        require(flow.source == null) {
            "Harness flow '${flow.name}' must not declare a production source"
        }
        require(additionalFlows.none { it.name == flow.name }) {
            "Additional flows must not duplicate harness flow '${flow.name}'"
        }
    }

    suspend fun execute(
        event: MuseEvent = MuseEvent(),
        transactionContext: Any? = null,
    ): MuseEvent = executeWithContext(event, transactionContext).event

    suspend fun executeWithContext(
        event: MuseEvent = MuseEvent(),
        transactionContext: Any? = null,
        claimReplyResources: ((MuseEvent) -> Set<MuseCursorProvider<out MuseCursor>>)? = null,
    ): EngineFlowExecution {
        factory.registerSource(HarnessSourceConfig::class) { component, _, _ ->
            HarnessMuseSource(component)
        }

        val runnableFlow =
            flow.copy(
                initialState = FlowInitialState.STARTED,
                source =
                    HarnessSourceConfig(
                        docName = "${flow.name} test source",
                        docId = "${flow.docId}-test-source",
                    ),
            )
        val application =
            bootstrap(
                config =
                    MuseApplicationConfig(
                        flows = listOf(runnableFlow) + additionalFlows,
                        configs = configs,
                        factory = factory,
                    ),
                dispatcher = StandardTestDispatcher(testScope.testScheduler),
            )

        return try {
            application.init()
            val replyResources = claimReplyResources?.let { StreamingResourceRegistry() }
            val envelope =
                MuseEventEnvelope(
                    event = event,
                    transactionContext = transactionContext,
                    replyResources = replyResources,
                )

            application.channelFor(runnableFlow.name).send(envelope)
            testScope.advanceUntilIdle()

            check(envelope.replyTo.isCompleted) {
                "Flow '${runnableFlow.name}' did not complete after virtual time became idle"
            }
            val resultEvent =
                try {
                    envelope.replyTo.await()
                } catch (failure: Exception) {
                    replyResources?.releaseAll()
                    testScope.advanceUntilIdle()
                    throw failure
                }
            // The reply owner builds its response while the reply registry is open, registers any
            // response-created providers, retains exactly the exported ones and then closes it.
            val lease =
                if (replyResources != null && claimReplyResources != null) {
                    val exported = claimReplyResources(resultEvent)
                    val retained =
                        exported.map { provider ->
                            replyResources.register(provider)
                            replyResources.retain(listOf(provider))
                        }
                    replyResources.releaseAll()
                    testScope.advanceUntilIdle()
                    retained
                } else {
                    replyResources?.releaseAll()
                    emptyList()
                }
            EngineFlowExecution(
                event = resultEvent,
                properties = Properties().apply { putAll(application.context.properties) },
                // The reply owner closes these after serialization, HEAD/no-body handling,
                // disconnect or response-construction failure.
                replyLeases = lease,
            )
        } finally {
            application.stop()
            testScope.advanceUntilIdle()
        }
    }
}

data class EngineFlowExecution(
    val event: MuseEvent,
    val properties: Properties,
    val replyLeases: List<StreamingResourceLease> = emptyList(),
)

internal data class HarnessSourceConfig(
    override val docName: String,
    override val docId: String,
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
) : Source

internal class HarnessMuseSource(
    config: HarnessSourceConfig,
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
