package dev.museframework.engine.api.execution

import dev.museframework.dsl.api.model.component.MuseComponent
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.engine.api.streaming.StreamingResourceRegistry
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope

data class ExecutionContext(
    val event: MuseEvent,
    val errorHandler: ResolvedErrorHandler?,
    val callStack: PersistentList<MuseStackFrame> = persistentListOf(),
    val parent: ExecutionContext? = null,
    val coroutineScope: CoroutineScope,
    val expressionEvaluator: MuseExpressionEvaluator,
    val streamingResources: StreamingResourceRegistry = StreamingResourceRegistry(),
    val currentScope: PipelineScope? = null,
) {
    val currentFrame: MuseStackFrame?
        get() = callStack.lastOrNull()

    val currentComponent: MuseComponent?
        get() = currentFrame?.element

    fun withEvent(event: MuseEvent): ExecutionContext = copy(event = event)

    fun withError(error: MuseError): ExecutionContext = copy(event = event.copy(error = error))

    fun withScope(scope: PipelineScope): ExecutionContext = copy(currentScope = scope)

    fun pushFrame(
        element: MuseComponent,
        index: Int,
    ): ExecutionContext =
        copy(
            callStack =
                callStack.add(
                    MuseStackFrame(
                        scope = currentScope ?: error("No current scope"),
                        element = element,
                        index = index,
                    ),
                ),
        )

    fun popFrame(): ExecutionContext = copy(callStack = callStack.removeAt(callStack.size - 1))

    fun childContext(
        event: MuseEvent,
        errorHandler: ResolvedErrorHandler? = null,
        coroutineScope: CoroutineScope,
    ): ExecutionContext =
        ExecutionContext(
            event = event,
            errorHandler = errorHandler ?: this.errorHandler,
            parent = this,
            coroutineScope = coroutineScope,
            expressionEvaluator = this.expressionEvaluator,
            streamingResources = this.streamingResources,
            currentScope = currentScope,
        )

    fun resolveErrorHandler(): ResolvedErrorHandler? = errorHandler ?: parent?.resolveErrorHandler()
}
