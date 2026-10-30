package dev.museframework.engine.api.streaming

import java.util.Collections
import java.util.IdentityHashMap

class StreamingResourceRegistry {
    private val lock = Any()
    private var released = false
    private var draining = false
    private val providers = IdentityHashMap<Any, Registration>()
    private var first: Registration? = null
    private var last: Registration? = null

    val isReleased: Boolean get() = synchronized(lock) { released }

    /**
     * Managed handles are weakly tracked through independent cleanup state.
     * Unadopted custom providers retain their original strong identity contract.
     */
    fun <T : MuseCursorProvider<out MuseCursor>> register(provider: T): T {
        val registration = Registration(provider)
        val releaseImmediately = synchronized(lock) {
            if (released) {
                StreamingProviderManagement.beginUnmanagedCleanup(provider)
            } else {
                if (!providers.containsKey(registration.identity) && StreamingProviderManagement.retain(provider)) {
                    addRegistration(registration)
                    registration.state?.track(this)
                }
                false
            }
        }
        if (releaseImmediately) cleanup(registration)
        return provider
    }

    /**
     * Acquires independent claims atomically, pinning managed application handles
     * until the returned lease closes. Provider callbacks run outside locks.
     * Direct close/release on a managed identity bypasses its ownership protocol.
     */
    fun retain(providers: Collection<MuseCursorProvider<out MuseCursor>>): StreamingResourceLease {
        val identities = Collections.newSetFromMap(IdentityHashMap<MuseCursorProvider<out MuseCursor>, Boolean>())
        identities.addAll(providers)
        val retained = StreamingResourceRegistry()
        identities.forEach { provider -> retained.addRegistration(Registration(provider)) }
        val lease = StreamingResourceLease(retained, identities.toList())
        identities.forEach { check(!it.isClosed) { "Cannot retain a closed or closing provider" } }
        synchronized(lock) {
            check(!released) { "Cannot retain resources from a released registry" }
            require(retained.providers.keys.all(this.providers::containsKey)) { "Cannot retain a provider not owned by this registry" }
            StreamingProviderManagement.retainOwned(identities)
            retained.providers.values.forEach { it.state?.track(retained) }
        }
        return lease
    }

    /** Transfers the sole registry claim without closing the provider. */
    fun transfer(provider: MuseCursorProvider<out MuseCursor>): Boolean = synchronized(lock) {
        val identity = StreamingProviderManagement.identity(provider)
        if (!released && providers.containsKey(identity) && StreamingProviderManagement.transfer(provider)) {
            removeRegistration(identity)?.state?.untrack(this)
            true
        } else false
    }

    fun releaseAll() {
        synchronized(lock) {
            released = true
            if (draining || providers.isEmpty()) return
            draining = true
        }
        val failures = mutableListOf<Throwable>()
        // Drain progressively, avoiding an execution-sized snapshot under memory pressure.
        try {
            while (true) {
                val registration = synchronized(lock) {
                    first?.let { removeRegistration(it.identity) }
                } ?: break
                registration.state?.untrack(this)
                if (StreamingProviderManagement.beginRegistryRelease(registration.identity)) {
                    try { cleanup(registration) } catch (failure: StreamingCleanupException) { failures.addAll(failure.failures) }
                }
            }
        } finally {
            synchronized(lock) { draining = false }
        }
        if (failures.isNotEmpty()) throw StreamingCleanupException(failures)
    }

    internal fun forgetManaged(state: ManagedStreamingCleanup) {
        synchronized(lock) { removeRegistration(state) }
    }

    // The identity index and linked drain order are mutated together under lock;
    // retained registries may also be prepared privately before publication.
    private fun addRegistration(registration: Registration) {
        providers[registration.identity] = registration
        registration.previous = last
        last?.next = registration
        if (first == null) first = registration
        last = registration
    }

    private fun removeRegistration(identity: Any): Registration? {
        val registration = providers.remove(identity) ?: return null
        val previous = registration.previous
        val next = registration.next
        if (previous == null) first = next else previous.next = next
        if (next == null) last = previous else next.previous = previous
        registration.previous = null
        registration.next = null
        return registration
    }

    companion object {
        /** Counts owned identities/claims without retaining application handles. */
        fun statistics(): StreamingResourceStatistics = StreamingProviderManagement.statistics()

        /** Owns an independent, strongly pinned claim for an adapter-created body. */
        fun ownedClaim(provider: MuseCursorProvider<out MuseCursor>): StreamingResourceLease {
            val owner = StreamingResourceRegistry()
            owner.register(provider)
            return owner.retain(listOf(provider)).also { owner.releaseAll() }
        }

        private fun cleanup(registration: Registration) {
            val state = registration.state
            if (state != null) {
                state.release()
                return
            }
            val provider = checkNotNull(registration.raw)
            val failures = mutableListOf<Throwable>()
            try {
                try { provider.close() } catch (failure: Throwable) { failures.add(failure) }
                try { provider.releaseResources() } catch (failure: Throwable) { failures.add(failure) }
            } finally {
                StreamingProviderManagement.finishCleanup(registration.identity)
            }
            if (failures.isNotEmpty()) throw StreamingCleanupException(failures)
        }
    }

    private class Registration(provider: MuseCursorProvider<out MuseCursor>) {
        val state = (provider as? ManagedStreamingHandle)?.cleanupState
        val raw = if (state == null) provider else null
        val identity: Any = state ?: provider
        var previous: Registration? = null
        var next: Registration? = null
    }
}
