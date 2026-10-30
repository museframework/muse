package dev.museframework.engine.dispatcher

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

object CoroutineDispatcherProvider {

    private const val PROPERTY_NAME = "muse.coroutine.dispatcher"
    const val VIRTUAL = "virtual"
    const val PLATFORM = "platform"

    fun fromSystemProperty(): CoroutineDispatcher {
        val mode = System.getProperty(PROPERTY_NAME, VIRTUAL).trim().lowercase()
        return when (mode) {
            VIRTUAL -> virtual()
            PLATFORM -> platform()
            else -> {
                System.err.println(
                    "WARNING: Unknown '$PROPERTY_NAME' value '$mode'. " +
                    "Valid values: $VIRTUAL, $PLATFORM. Falling back to $VIRTUAL."
                )
                virtual()
            }
        }
    }

    private val virtualDispatcher by lazy {
        Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
    }

    fun virtual(): CoroutineDispatcher = virtualDispatcher

    fun platform(): CoroutineDispatcher = Dispatchers.Default
}
