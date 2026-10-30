package dev.museframework.engine.integration

import dev.museframework.dsl.api.model.flow.FlowInitialState
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.engine.api.execution.policy.BackgroundExecutionConfig
import dev.museframework.engine.api.source.MuseEventEnvelope
import dev.museframework.engine.app.MuseApplication
import dev.museframework.engine.app.MuseApplicationConfig
import dev.museframework.engine.app.bootstrap
import dev.museframework.engine.factory.MuseComponentFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

/**
 * Opens a started application over virtual time without draining it or stopping it, so a test can
 * observe a reply while accepted background work is still running and release its barriers in a
 * controlled order. Always [close] the session in a `finally` block.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class EngineFlowSession private constructor(
    val application: MuseApplication,
    val channel: SendChannel<MuseEventEnvelope>,
) {
    companion object {
        suspend fun open(
            scope: TestScope,
            flow: MuseFlow.Flow,
            factory: MuseComponentFactory,
            background: BackgroundExecutionConfig = BackgroundExecutionConfig(),
        ): EngineFlowSession {
            require(flow.source == null) {
                "Session flow '${flow.name}' must not declare a production source"
            }
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
                            flows = listOf(runnableFlow),
                            factory = factory,
                            background = background,
                        ),
                    dispatcher = StandardTestDispatcher(scope.testScheduler),
                )
            application.init()
            application.start()
            return EngineFlowSession(application, application.channelFor(runnableFlow.name))
        }
    }

    /** Stops the application and joins background finalization. */
    suspend fun close() {
        application.stop()
    }
}
