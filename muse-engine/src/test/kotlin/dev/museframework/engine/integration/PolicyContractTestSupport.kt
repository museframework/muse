package dev.museframework.engine.integration

import dev.museframework.dsl.api.model.component.PipelineElement
import dev.museframework.dsl.api.model.component.Router
import dev.museframework.dsl.api.model.error.ErrorHandler
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.execution.policy.PolicyDefinition
import dev.museframework.engine.api.execution.policy.PolicyFactory
import dev.museframework.engine.api.execution.policy.PolicyRouterDeclaration
import dev.museframework.engine.api.spi.MuseContext
import dev.museframework.engine.factory.MuseComponentFactory
import dev.museframework.engine.factory.MuseEngineRegistrar
import dev.museframework.test.policy.DefaultOnFailureRegistrar
import dev.museframework.engine.app.MuseApplication
import dev.museframework.engine.app.MuseApplicationConfig
import dev.museframework.engine.app.bootstrap
import dev.museframework.engine.api.execution.MuseProcessor
import dev.museframework.engine.api.streaming.MuseCursorStream
import dev.museframework.engine.api.streaming.MuseCursorStreamProvider
import dev.museframework.engine.execution.policy.BackgroundTaskManager
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

internal fun policyFactory(
    actions: Map<String, suspend (ExecutionContext) -> ProcessorResult> = emptyMap(),
): MuseComponentFactory = MuseComponentFactory().also { factory ->
    MuseEngineRegistrar().register(factory)
    DefaultOnFailureRegistrar().register(factory)
    factory.registerProcessor(CaptureConfig::class) { component, _ ->
        CaptureMuseProcessor(component) { context ->
            actions[component.docId]?.invoke(context) ?: ProcessorResult.Success(context)
        }
    }
    factory.registerPolicyRouter(ProbePolicyConfig::class, PolicyRouterDeclaration { component, definition, context ->
        component.declare(definition, context)
    })
}

internal fun policyFlow(
    body: List<PipelineElement>,
    handler: ErrorHandler? = null,
): MuseFlow.Flow = MuseFlow.Flow(
    name = "policy-contract",
    docId = "policy-contract",
    pipeline = body,
    errorHandler = handler,
)

internal class ProbePolicyConfig(
    override val docName: String = "probe",
    override val docId: String = "probe",
    val declare: (PolicyDefinition, MuseContext) -> PolicyFactory,
) : Router {
    override val docDescription: String? = null
    override val sourceFile: String? = null
    override val sourceLine: Int? = null
    override val sourceClass: String? = null
}

internal suspend fun <T> withPreparedPolicy(
    config: Router,
    factory: MuseComponentFactory = policyFactory(),
    block: suspend (MuseApplication, MuseProcessor) -> T,
): T {
    val flow = policyFlow(listOf(config))
    val app = bootstrap(MuseApplicationConfig(listOf(flow), factory = factory))
    return try {
        app.init()
        block(app, app.context.policyBindings!!.pipeline(flow).elements.single().processor)
    } finally { app.stop() }
}

internal class PolicyTrackingProvider : MuseCursorStreamProvider {
    override var isClosed = false
    var releases = 0
    override fun openCursor(): MuseCursorStream = error("This lifecycle fixture does not open cursors")
    override fun close() { isClosed = true }
    override fun releaseResources() { releases++ }
}

/** Collects the engine's terminal background diagnostics without a new observer interface. */
internal class BackgroundLogCapture(
    private val failOnAppend: Boolean = false,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(BackgroundTaskManager::class.java) as ch.qos.logback.classic.Logger
    private val appender =
        object : ListAppender<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                // An Error is not swallowed by logback's appender guard, so this sink models a
                // diagnostic backend that genuinely fails.
                if (failOnAppend) throw DiagnosticSinkFailure("diagnostic sink unavailable")
                super.append(event)
            }
        }.apply { start() }

    init {
        logger.addAppender(appender)
    }

    val events: List<ILoggingEvent>
        get() = appender.list.toList()

    override fun close() {
        logger.detachAppender(appender)
    }
}

internal class DiagnosticSinkFailure(message: String) : Error(message)
