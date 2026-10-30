package dev.museframework.engine.api.execution.policy

import dev.museframework.engine.api.spi.MuseContext

fun interface PolicyFactory {
    fun create(context: MuseContext): ExecutionPolicy
}
