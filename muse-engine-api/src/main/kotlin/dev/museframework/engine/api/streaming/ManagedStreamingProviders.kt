package dev.museframework.engine.api.streaming

import java.lang.ref.Cleaner
import java.lang.ref.Reference
import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Application handles are separate from the state retained for eventual cleanup. */
internal interface ManagedStreamingHandle {
    val cleanupState: ManagedStreamingCleanup
}

internal object ManagedStreamingProviders {
    private val lock = Any()
    private val cleaner = Cleaner.create()
    private val states = IdentityHashMap<MuseCursorProvider<out MuseCursor>, ManagedStreamingCleanup>()

    fun stream(provider: MuseCursorStreamProvider, knownSize: Long = -1L): MuseCursorStreamProvider {
        if (provider is ManagedStreamingHandle) return provider
        return adopt(provider, knownSize) { state ->
            if (provider is BackgroundStreamPreparation) PreparedStreamProvider(state)
            else ManagedStreamProvider(state)
        } as MuseCursorStreamProvider
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> iterator(provider: MuseCursorIteratorProvider<T>, knownSize: Long = -1L): MuseCursorIteratorProvider<T> {
        if (provider is ManagedStreamingHandle) return provider
        return adopt(provider, knownSize) { state ->
            if (provider is BackgroundStreamPreparation) PreparedIteratorProvider<T>(state)
            else ManagedIteratorProvider<T>(state)
        } as MuseCursorIteratorProvider<T>
    }

    private fun adopt(
        provider: MuseCursorProvider<out MuseCursor>,
        knownSize: Long,
        create: (ManagedStreamingCleanup) -> MuseCursorProvider<out MuseCursor>,
    ): MuseCursorProvider<out MuseCursor> {
        // Provider callbacks must not execute while holding a factory/ownership lock.
        check(!provider.isClosed) { "Cannot adopt a closed streaming provider" }
        synchronized(lock) {
            states[provider]?.let { state ->
                if (state.isReleasing) throw StreamingResourceReleasedException()
                return state.handle.get()
                    ?: throw StreamingResourceReleasedException()
            }
            check(!StreamingProviderManagement.isManaged(provider)) { "Cannot adopt an already registered raw provider" }
            val state = ManagedStreamingCleanup(provider, knownSize)
            val handle = create(state)
            state.handle = WeakReference(handle)
            state.cleanable = cleaner.register(handle, state)
            states[provider] = state
            Reference.reachabilityFence(handle)
            return handle
        }
    }

    fun <T> withRawRegistration(provider: MuseCursorProvider<out MuseCursor>, block: () -> T): T =
        synchronized(lock) {
            require(!states.containsKey(provider)) { "Register the managed handle returned by StreamingProviders.managed, not its adopted delegate" }
            block()
        }

    fun forget(provider: MuseCursorProvider<out MuseCursor>, state: ManagedStreamingCleanup) {
        synchronized(lock) {
            if (states[provider] === state) states.remove(provider)
        }
    }
}

internal class ManagedStreamingCleanup(
    delegate: MuseCursorProvider<out MuseCursor>,
    initialSize: Long,
) : Runnable {
    private val lock = Any()
    private val releasing = AtomicBoolean(false)
    @Volatile private var delegate: MuseCursorProvider<out MuseCursor>? = delegate
    @Volatile private var knownSize: Long = initialSize
    private val registries = mutableListOf<WeakReference<StreamingResourceRegistry>>()
    lateinit var handle: WeakReference<MuseCursorProvider<out MuseCursor>>
    var cleanable: Cleaner.Cleanable? = null

    val isReleasing: Boolean get() = releasing.get()
    val isClosed: Boolean get() = isReleasing || delegate?.isClosed != false

    fun provider(): MuseCursorProvider<out MuseCursor> =
        delegate?.takeUnless { releasing.get() } ?: throw StreamingResourceReleasedException()

    fun size(): Long {
        val resource = delegate
        if (resource != null && !isReleasing) knownSize = resourceSize(resource)
        return knownSize
    }

    private fun resourceSize(resource: MuseCursorProvider<out MuseCursor>): Long = when (resource) {
        is MuseCursorStreamProvider -> resource.size
        is MuseCursorIteratorProvider<*> -> resource.size
        else -> -1L
    }

    fun track(registry: StreamingResourceRegistry) {
        synchronized(lock) {
            check(!releasing.get()) { "Cannot register a provider whose cleanup has started" }
            registries.add(WeakReference(registry))
        }
    }

    fun untrack(registry: StreamingResourceRegistry) {
        synchronized(lock) { registries.removeAll { it.get() == null || it.get() === registry } }
    }

    fun release() {
        try {
            cleanup()
        } finally {
            cleanable?.clean()
        }
    }

    override fun run() {
        try {
            cleanup()
        } catch (failure: Throwable) {
            System.getLogger(ManagedStreamingCleanup::class.java.name)
                .log(System.Logger.Level.ERROR, "Failed to reclaim an abandoned streaming provider", failure)
        }
    }

    private fun cleanup() {
        if (!releasing.compareAndSet(false, true)) return
        val resource = delegate ?: return
        val failures = mutableListOf<Throwable>()
        try {
            try { resource.close() } catch (failure: Throwable) { failures.add(failure) }
            try { resource.releaseResources() } catch (failure: Throwable) { failures.add(failure) }
        } finally {
            delegate = null
            StreamingProviderManagement.forgetManaged(this)
            ManagedStreamingProviders.forget(resource, this)
            while (true) {
                val registry = synchronized(lock) {
                    if (registries.isEmpty()) null else registries.removeAt(registries.lastIndex)
                } ?: break
                registry.get()?.forgetManaged(this)
            }
        }
        if (failures.isNotEmpty()) throw StreamingCleanupException(failures)
    }
}

private open class ManagedStreamProvider(
    final override val cleanupState: ManagedStreamingCleanup,
) : MuseCursorStreamProvider, ManagedStreamingHandle {
    override val isClosed: Boolean get() = withPin(this) { cleanupState.isClosed }
    override val size: Long get() = withPin(this) { cleanupState.size() }
    override fun openCursor(): MuseCursorStream = withPin(this) {
        val cursor = (cleanupState.provider() as MuseCursorStreamProvider).openCursor()
        ManagedStreamCursor(cursor, this)
    }
    override fun close() = withPin(this) { cleanupState.release() }
    override fun releaseResources() = withPin(this) { cleanupState.release() }
}

private class PreparedStreamProvider(state: ManagedStreamingCleanup) : ManagedStreamProvider(state), BackgroundStreamPreparation {
    override suspend fun prepareForBackgroundUse() {
        try { (cleanupState.provider() as BackgroundStreamPreparation).prepareForBackgroundUse() }
        finally { Reference.reachabilityFence(this) }
    }
}

private open class ManagedIteratorProvider<T>(
    final override val cleanupState: ManagedStreamingCleanup,
) : MuseCursorIteratorProvider<T>, ManagedStreamingHandle {
    @Suppress("UNCHECKED_CAST")
    private fun delegate() = cleanupState.provider() as MuseCursorIteratorProvider<T>
    override val isClosed: Boolean get() = withPin(this) { cleanupState.isClosed }
    override val size: Long get() = withPin(this) { cleanupState.size() }
    override fun openCursor(): MuseCursorIterator<T> = withPin(this) { ManagedIteratorCursor(delegate().openCursor(), this) }
    override fun close() = withPin(this) { cleanupState.release() }
    override fun releaseResources() = withPin(this) { cleanupState.release() }
}

private class PreparedIteratorProvider<T>(state: ManagedStreamingCleanup) : ManagedIteratorProvider<T>(state), BackgroundStreamPreparation {
    override suspend fun prepareForBackgroundUse() {
        try { (cleanupState.provider() as BackgroundStreamPreparation).prepareForBackgroundUse() }
        finally { Reference.reachabilityFence(this) }
    }
}

private inline fun <T> withPin(owner: Any, block: () -> T): T =
    try { block() } finally { Reference.reachabilityFence(owner) }

// Standalone decorators avoid an implicit strong outer reference after close.
private class ManagedStreamCursor(
    private val delegate: MuseCursorStream,
    owner: MuseCursorStreamProvider,
) : MuseCursorStream() {
    private var ownerPin: MuseCursorStreamProvider? = owner
    private val closed = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    override val provider: MuseCursorProvider<out MuseCursor> get() = ownerPin ?: delegate.provider
    override val position: Long get() = withPin(this) { delegate.position }
    override val isReleased: Boolean get() = withPin(this) { delegate.isReleased }
    override fun seek(position: Long) = withPin(this) { delegate.seek(position) }
    override fun read(): Int = withPin(this) { delegate.read() }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = withPin(this) { delegate.read(buffer, offset, length) }
    override fun available(): Int = withPin(this) { delegate.available() }
    override fun skip(count: Long): Long = withPin(this) { delegate.skip(count) }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { withPin(this) { delegate.close() } } finally { ownerPin = null }
        }
    }
    override fun release() {
        if (released.compareAndSet(false, true)) {
            try { withPin(this) { delegate.release() } } finally { closed.set(true); ownerPin = null }
        }
    }
}

private class ManagedIteratorCursor<T>(
    private val delegate: MuseCursorIterator<T>,
    owner: MuseCursorIteratorProvider<T>,
) : MuseCursorIterator<T> {
    private var ownerPin: MuseCursorIteratorProvider<T>? = owner
    private val closed = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    override val provider: MuseCursorProvider<out MuseCursor> get() = ownerPin ?: delegate.provider
    override val position: Long get() = withPin(this) { delegate.position }
    override val size: Long get() = withPin(this) { delegate.size }
    override val isReleased: Boolean get() = withPin(this) { delegate.isReleased }
    override fun hasNext(): Boolean = withPin(this) { delegate.hasNext() }
    override fun next(): T = withPin(this) { delegate.next() }
    override fun seek(position: Long) = withPin(this) { delegate.seek(position) }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { withPin(this) { delegate.close() } } finally { ownerPin = null }
        }
    }
    override fun release() {
        if (released.compareAndSet(false, true)) {
            try { withPin(this) { delegate.release() } } finally { closed.set(true); ownerPin = null }
        }
    }
}
