package dev.museframework.engine.execution.policy

import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.execution.projectTarget
import dev.museframework.engine.api.execution.policy.BackgroundAdmission
import dev.museframework.engine.api.execution.policy.BackgroundHandle
import dev.museframework.engine.api.execution.policy.BodyHandle
import dev.museframework.engine.api.execution.policy.PolicyInvocation
import dev.museframework.engine.api.execution.policy.PolicyGroup
import dev.museframework.engine.api.execution.policy.PolicyOutcome
import dev.museframework.engine.api.streaming.MuseCursor
import dev.museframework.engine.api.streaming.MuseCursorProvider
import dev.museframework.engine.api.streaming.StreamingEventResources
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

internal class ManagedPolicyInvocation(
    private val application: PolicyBindings,
    private val binding: Any,
    private val context: ExecutionContext,
) : PolicyInvocation {
    @Volatile private var open = true

    override val input: MuseEvent
        get() { checkOpen(); return context.event }

    override suspend fun call(body: BodyHandle, event: MuseEvent, timeoutMillis: Long?): PolicyOutcome {
        checkOpen()
        currentCoroutineContext().ensureActive()
        val boundBody = validateBody(body)
        require(timeoutMillis == null || timeoutMillis > 0) { "Call timeout must be positive" }
        StreamingEventResources.registerEventProviders(event, context.streamingResources)
        val result = if (timeoutMillis == null) boundBody.execution.invoke(context.withEvent(event))
            else withTimeoutOrNull(timeoutMillis) { boundBody.execution.invoke(context.withEvent(event)) }
        // withTimeoutOrNull recognizes only its own timer; enclosing timeouts remain cancellation.
        val outcome = when (result) {
            null -> {
                currentCoroutineContext().ensureActive()
                val error = MuseError(
                    description = "Policy body timed out",
                    detailedDescription = "Policy body exceeded ${timeoutMillis}ms",
                    errorType = ErrorType.Mule.TIMEOUT,
                    cause = IllegalStateException("Policy body timeout"),
                    sourceFile = context.currentComponent?.sourceFile,
                    sourceLine = context.currentComponent?.sourceLine,
                    sourceClass = context.currentComponent?.sourceClass,
                )
                PolicyOutcome.Failure(error, event.copy(error = error))
            }
            is ProcessorResult.Success -> PolicyOutcome.Success(result.context.event.copy(error = event.error))
            is ProcessorResult.Failure -> PolicyOutcome.Failure(result.error, result.context.event.copy(error = result.error))
        }
        StreamingEventResources.registerEventProviders(outcome.event, context.streamingResources)
        return outcome
    }

    override suspend fun pause(millis: Long) {
        checkOpen()
        require(millis >= 0) { "Pause duration must be nonnegative" }
        currentCoroutineContext().ensureActive()
        delay(millis)
    }

    override suspend fun <T> group(maxConcurrency: Int?, block: suspend PolicyGroup.() -> T): T {
        checkOpen()
        require(maxConcurrency == null || maxConcurrency > 0) { "Group concurrency must be positive" }
        return coroutineScope {
            val group = ManagedPolicyGroup(this@ManagedPolicyInvocation, this, context, maxConcurrency)
            try { group.block() } finally { group.close() }
        }
    }

    internal fun validateBody(body: BodyHandle): BoundBody {
        checkOpen()
        require(body is BoundBody && body.application === application && body.binding === binding) {
            "Body handle does not belong to this policy binding"
        }
        return body
    }

    internal fun child(context: ExecutionContext): ManagedPolicyInvocation =
        ManagedPolicyInvocation(application, binding, context)

    internal fun retain(event: MuseEvent) {
        checkOpen()
        StreamingEventResources.registerEventProviders(event, context.streamingResources)
    }

    override fun project(outcome: PolicyOutcome, target: TargetConfig, base: MuseEvent): PolicyOutcome {
        checkOpen()
        return context.projectTarget(outcome, target, base)
    }

    override fun <T : MuseCursorProvider<out MuseCursor>> manage(provider: T): T {
        checkOpen()
        return context.streamingResources.register(provider)
    }

    override suspend fun submit(
        background: BackgroundHandle,
        body: BodyHandle,
        event: MuseEvent,
    ): BackgroundAdmission {
        checkOpen()
        currentCoroutineContext().ensureActive()
        val handle = validateBackground(background)
        val boundBody = validateBody(body)
        return application.background.submit(handle, boundBody, event, context)
    }

    private fun validateBackground(background: BackgroundHandle): BoundBackgroundHandle {
        checkOpen()
        require(
            background is BoundBackgroundHandle &&
                background.application === application &&
                background.binding === binding,
        ) {
            "Background handle does not belong to this policy binding"
        }
        return background
    }

    fun close() { open = false }

    private fun checkOpen() {
        application.checkActive()
        check(open) { "Policy invocation is closed" }
    }
}
