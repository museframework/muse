package dev.museframework.engine.execution

import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.MDC

internal suspend fun <T> withCorrelationIdMdc(
    correlationId: String,
    block: suspend () -> T,
): T {
    val contextMap = MDC.getCopyOfContextMap()?.toMutableMap() ?: mutableMapOf()
    contextMap["correlationId"] = correlationId
    return withContext(MDCContext(contextMap)) {
        block()
    }
}
