package dev.museframework.engine.app

import dev.museframework.dsl.api.config.ConfigurationPropertiesConfig
import dev.museframework.dsl.api.config.SchedulerConfig
import dev.museframework.dsl.api.model.config.Configuration
import dev.museframework.dsl.api.model.config.ConfigurationProperties
import dev.museframework.dsl.api.model.config.EnvironmentProperties
import dev.museframework.dsl.api.model.config.GlobalProperty
import dev.museframework.dsl.api.model.config.JvmSystemProperties
import dev.museframework.dsl.api.model.config.MuseConfiguration
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.dsl.validation.ValidationError
import dev.museframework.dsl.validation.validateFlowRefs
import dev.museframework.engine.context.MuseContext
import dev.museframework.engine.dataweave.MuseDataWeaveExecutor
import dev.museframework.engine.api.execution.Pipeline
import dev.museframework.engine.api.execution.ResolvedErrorHandler
import dev.museframework.engine.api.execution.ResolvedErrorHandlerStrategy
import dev.museframework.engine.expression.MuseExpressionEvaluatorImpl
import dev.museframework.engine.dispatcher.CoroutineDispatcherProvider
import dev.museframework.engine.execution.policy.BackgroundTaskManager
import dev.museframework.engine.execution.policy.PolicyBindings
import kotlinx.coroutines.CoroutineDispatcher
import java.util.*

fun bootstrap(
    config: MuseApplicationConfig,
    dispatcher: CoroutineDispatcher = CoroutineDispatcherProvider.fromSystemProperty(),
): MuseApplication {
    val flowMap = config.flows.associateBy { it.name }

    val properties = Properties()

    val expressionEvaluator = MuseExpressionEvaluatorImpl(
        dataWeaveExecutor = MuseDataWeaveExecutor(),
        properties = properties,  // riferimento condiviso
    )

    val globalErrorHandlerRef = config.configs
        .filterIsInstance<Configuration>()
        .firstOrNull()
        ?.defaultErrorHandlerRef

    val globalHandlerFlow = globalErrorHandlerRef?.let { ref ->
        config.flows
            .filterIsInstance<MuseFlow.Flow>()
            .firstOrNull { it.name == ref }
    }

    val context = MuseContext(
        flows = flowMap,
        configs = config.configs.associateBy { it.docName },
        globalErrorHandler = null,
        defaultErrorHandler = buildDefaultErrorHandler(),
        factory = config.factory.frozenCopy(),
        properties = properties,
        expressionEvaluator = expressionEvaluator,
    )

    // Apply every declaration: documentation labels are not unique identifiers.
    // Load property sources first, retaining declaration order within both groups.
    val (propertySources, otherConfigs) = config.configs.partition { it.isPropertySource() }
    (propertySources + otherConfigs).forEach { cfg ->
        context.factory.applyConfig(cfg, context)
    }

    val schedulerErrors =
        flowMap.values
            .filterIsInstance<MuseFlow.Flow>()
            .filter { it.source is SchedulerConfig && it.pipeline.isEmpty() }
            .map {
                ValidationError(
                    "Scheduler flow '${it.name}' must contain at least one processor or router after the scheduler source",
                )
            }

    val errors = validateFlowRefs(flowMap) + schedulerErrors
    if (errors.isNotEmpty()) {
        throw MuseBootstrapException(errors)
    }

    val background = BackgroundTaskManager(config.background, dispatcher)
    context.attachPolicyBindings(globalHandlerFlow, background)
    return MuseApplication(context, dispatcher, background)
}

/**
 * Attaches the application-local declaration bindings of [this] context.
 *
 * Handler definitions resolve here, at bootstrap, and [PolicyBindings.prepare] links their bodies at
 * init: a global or local handler flow may contain managed policies like any other flow, and no
 * body is ever compiled recursively while another part of the application is still unbound.
 */
internal fun MuseContext.attachPolicyBindings(
    globalHandlerFlow: MuseFlow.Flow?,
    background: BackgroundTaskManager,
): PolicyBindings =
    policyBindings ?: PolicyBindings(this, globalHandlerFlow, background).also {
        it.discover()
        policyBindings = it
    }

/**
 * Resolves the flow selected by the application's `defaultErrorHandlerRef`, taking the first
 * matching declaration so a same-named flow cannot shadow the configured global handler.
 */
internal fun MuseContext.resolveGlobalHandlerFlow(): MuseFlow.Flow? {
    val reference =
        configs.values.filterIsInstance<Configuration>().firstOrNull()?.defaultErrorHandlerRef
            ?: return null
    return flows.values.filterIsInstance<MuseFlow.Flow>().firstOrNull { it.name == reference }
}

private fun MuseConfiguration.isPropertySource(): Boolean =
    this is GlobalProperty ||
        this is ConfigurationProperties ||
        this is ConfigurationPropertiesConfig ||
        this is EnvironmentProperties ||
        this is JvmSystemProperties

private fun buildDefaultErrorHandler(): ResolvedErrorHandler =
    ResolvedErrorHandler(
        strategies = listOf(
            ResolvedErrorHandlerStrategy(
                errorType = ErrorType.Mule.ANY,
                pipeline = Pipeline(emptyList()),
                kind = ResolvedErrorHandlerStrategy.Kind.PROPAGATE,
            )
        )
    )
