package dev.museframework.engine.api.streaming

import java.util.IdentityHashMap

/** Tracks registry ownership by provider identity, including shared providers. */
internal object StreamingProviderManagement {
    private val lock = Any()
    private val ownershipCounts = IdentityHashMap<Any, Int>()

    fun identity(provider: MuseCursorProvider<out MuseCursor>): Any =
        (provider as? ManagedStreamingHandle)?.cleanupState ?: provider

    /** Returns false when cleanup for this identity is already in progress. */
    fun retain(provider: MuseCursorProvider<out MuseCursor>): Boolean {
        fun acquire(): Boolean = synchronized(lock) {
            val identity = identity(provider)
            val count = ownershipCounts[identity]
            if (count == 0) return@synchronized false
            if (provider is ManagedStreamingHandle && provider.cleanupState.isReleasing) return@synchronized false
            ownershipCounts[identity] = (count ?: 0) + 1
            true
        }
        return if (provider is ManagedStreamingHandle) acquire()
        else ManagedStreamingProviders.withRawRegistration(provider, ::acquire)
    }

    /**
     * Adds a claim for distinct identities while the source registry is locked.
     * Validates the entire batch against management state only before incrementing,
     * so failed validation leaves no provisional claims to roll back. Provider
     * closure state is deliberately not read here: an `isClosed` implementation
     * must never run while a registry or management lock is held. A zero count
     * means cleanup already claimed the identity and cannot be resurrected.
     * Lock order remains registry then management.
     */
    fun retainOwned(providers: Set<MuseCursorProvider<out MuseCursor>>) {
        synchronized(lock) {
            providers.forEach { provider ->
                check((ownershipCounts[identity(provider)] ?: 0) > 0) {
                    "Cannot retain a provider whose cleanup has already started"
                }
            }
            providers.forEach { provider ->
                val identity = identity(provider)
                ownershipCounts[identity] = ownershipCounts.getValue(identity) + 1
            }
        }
    }

    /** Claims cleanup when the releasing registry was the last owner. */
    fun beginRegistryRelease(identity: Any): Boolean =
        synchronized(lock) {
            if (identity is ManagedStreamingCleanup && (identity.isReleasing || !ownershipCounts.containsKey(identity))) {
                return@synchronized false
            }
            val count = ownershipCounts[identity] ?: 1
            if (count <= 1) {
                ownershipCounts[identity] = 0
                true
            } else {
                ownershipCounts[identity] = count - 1
                false
            }
        }

    /** Claims immediate cleanup only when no registry owns the provider. */
    fun beginUnmanagedCleanup(provider: MuseCursorProvider<out MuseCursor>): Boolean {
        fun claim(): Boolean = synchronized(lock) {
            val identity = identity(provider)
            if (identity is ManagedStreamingCleanup && identity.isReleasing) return@synchronized false
            if (ownershipCounts.containsKey(identity)) return@synchronized false
            ownershipCounts[identity] = 0
            true
        }
        return if (provider is ManagedStreamingHandle) claim()
        else ManagedStreamingProviders.withRawRegistration(provider, ::claim)
    }

    /** Transfers the sole registry ownership to an external owner. */
    fun transfer(provider: MuseCursorProvider<out MuseCursor>): Boolean =
        synchronized(lock) {
            val identity = identity(provider)
            if (ownershipCounts[identity] != 1) return@synchronized false
            ownershipCounts.remove(identity)
            true
        }

    fun finishCleanup(identity: Any) {
        synchronized(lock) {
            if (ownershipCounts[identity] == 0) ownershipCounts.remove(identity)
        }
    }

    fun forgetManaged(state: ManagedStreamingCleanup) {
        synchronized(lock) { ownershipCounts.remove(state) }
    }

    fun isManaged(provider: MuseCursorProvider<out MuseCursor>): Boolean =
        synchronized(lock) { ownershipCounts.containsKey(identity(provider)) }

    fun statistics(): StreamingResourceStatistics = synchronized(lock) {
        var active = 0
        var claims = 0L
        var releasing = 0
        ownershipCounts.values.forEach { count ->
            if (count > 0) {
                active++
                claims += count
            } else {
                releasing++
            }
        }
        StreamingResourceStatistics(active, claims, releasing)
    }
}
