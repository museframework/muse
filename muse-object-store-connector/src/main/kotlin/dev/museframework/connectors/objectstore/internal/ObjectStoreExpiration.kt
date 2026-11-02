package dev.museframework.connectors.objectstore.internal

import dev.museframework.objectstore.api.config.ObjectStoreConfig
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

internal class ObjectStoreExpiration : AutoCloseable {
    private var executor: ScheduledExecutorService? = null

    fun schedule(store: H2ObjectStore) {
        val config = store.config
        if (!enabled(config)) return
        val scheduler = executor ?: Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "muse-object-store-expiration")
        }.also { executor = it }
        val interval = config.expirationInterval.toNanos()
        scheduler.scheduleWithFixedDelay({
            try { store.sweep() } catch (cause: Exception) {
                LOGGER.warn("Object Store expiration failed for {}", config.name, cause)
            }
        }, interval, interval, TimeUnit.NANOSECONDS)
    }

    override fun close() {
        val scheduler = executor ?: return
        scheduler.shutdownNow()
        var interrupted = false
        try {
            while (true) {
                try { if (scheduler.awaitTermination(1, TimeUnit.SECONDS)) return }
                catch (_: InterruptedException) { interrupted = true }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }

    private fun enabled(config: ObjectStoreConfig): Boolean =
        !config.expirationInterval.isNegative && !config.expirationInterval.isZero &&
            (config.maxEntries != null || config.entryTtl?.let { !it.isNegative && !it.isZero } == true)

    companion object { private val LOGGER = LoggerFactory.getLogger(ObjectStoreExpiration::class.java) }
}
