package dev.museframework.engine.context

import dev.museframework.engine.api.spi.ApplicationResources
import dev.museframework.engine.api.spi.ApplicationStartableResource

internal class ApplicationResourceRegistry : ApplicationResources, AutoCloseable {
    private val resources = mutableListOf<AutoCloseable>()
    private var closed = false
    private var started = false

    override fun register(resource: AutoCloseable) {
        synchronized(this) {
            if (!closed) {
                if (started && resource is ApplicationStartableResource) {
                    try {
                        resource.start()
                    } catch (failure: Throwable) {
                        try { resource.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
                        throw failure
                    }
                }
                resources += resource
                return
            }
        }
        resource.close()
        throw IllegalStateException("Application resources are already closed")
    }

    @Synchronized
    fun start() {
        check(!closed) { "Application resources are already closed" }
        if (started) return
        started = true
        resources.filterIsInstance<ApplicationStartableResource>().toList().forEach { it.start() }
    }

    override fun close() {
        val pending =
            synchronized(this) {
                if (closed) return
                closed = true
                resources.asReversed().toList().also { resources.clear() }
            }
        var failure: Throwable? = null
        pending.forEach { resource ->
            try {
                resource.close()
            } catch (closeFailure: Throwable) {
                if (failure == null) failure = closeFailure else failure.addSuppressed(closeFailure)
            }
        }
        failure?.let { throw it }
    }
}
