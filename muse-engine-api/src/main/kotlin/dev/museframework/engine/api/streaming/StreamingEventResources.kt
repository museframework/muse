package dev.museframework.engine.api.streaming

import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import java.util.Collections
import java.util.IdentityHashMap

object StreamingEventResources {
    /**
     * Discovers the provider identities in supported event containers without consuming providers
     * or lazy iterables. Discovery never acquires or releases a claim: registration and lifetime
     * belong to a [StreamingResourceRegistry].
     */
    fun eventProviders(event: MuseEvent): List<MuseCursorProvider<out MuseCursor>> = valueProviders(event)

    /** Discovers providers at a value boundary using the same cycle-safe event traversal. */
    fun valueProviders(root: Any?): List<MuseCursorProvider<out MuseCursor>> {
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val providers = mutableListOf<MuseCursorProvider<out MuseCursor>>()
        val pending = ArrayDeque<Any>()
        fun enqueue(value: Any?) {
            when (value) {
                is MuseCursorProvider<*>, is TypedValue<*>, is MuseEvent,
                is List<*>, is Map<*, *>, is Array<*> ->
                    if (visited.add(value)) pending.addLast(value)
            }
        }
        enqueue(root)
        while (pending.isNotEmpty()) {
            when (val value = pending.removeLast()) {
                is MuseCursorProvider<*> -> providers += value
                is TypedValue<*> -> enqueue(value.value)
                is MuseEvent -> {
                    enqueue(value.payload)
                    enqueue(value.attributes)
                    enqueue(value.variables)
                }
                is List<*> -> value.forEach(::enqueue)
                is Map<*, *> -> value.forEach { (key, entry) ->
                    enqueue(key)
                    enqueue(entry)
                }
                is Array<*> -> value.forEach(::enqueue)
            }
        }
        return providers
    }

    /** Discovers supported event containers without consuming providers or lazy iterables. */
    fun registerEventProviders(
        event: MuseEvent,
        registry: StreamingResourceRegistry,
    ) {
        registerValueProviders(event, registry)
    }

    /** Adopts providers in supported containers without consuming or wrapping raw streams. */
    fun registerValueProviders(value: Any?, registry: StreamingResourceRegistry) {
        valueProviders(value).forEach(registry::register)
    }
}
