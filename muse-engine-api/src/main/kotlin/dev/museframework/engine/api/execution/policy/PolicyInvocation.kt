package dev.museframework.engine.api.execution.policy

import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.streaming.MuseCursor
import dev.museframework.engine.api.streaming.MuseCursorProvider

/** Invocation-local capabilities; retaining and using this object after execute returns is invalid. */
interface PolicyInvocation {
    val input: MuseEvent

    suspend fun call(
        body: BodyHandle,
        event: MuseEvent = input,
        timeoutMillis: Long? = null,
    ): PolicyOutcome

    suspend fun pause(millis: Long)

    /** Cancels and joins remaining tasks before returning the block's value. */
    suspend fun <T> group(
        maxConcurrency: Int? = null,
        block: suspend PolicyGroup.() -> T,
    ): T

    fun project(
        outcome: PolicyOutcome,
        target: TargetConfig,
        base: MuseEvent = input,
    ): PolicyOutcome

    fun <T : MuseCursorProvider<out MuseCursor>> manage(provider: T): T

    /**
     * Submits [event] to application-owned background work running [body], under [background]'s
     * declared limits. A malformed handle or a closed invocation is a contract defect.
     */
    suspend fun submit(
        background: BackgroundHandle,
        body: BodyHandle,
        event: MuseEvent = input,
    ): BackgroundAdmission
}
