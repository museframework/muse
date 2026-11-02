package dev.museframework.engine.api.spi

/** Registers resources whose lifetime is the owning Muse application. */
interface ApplicationResources {
    fun register(resource: AutoCloseable)
}

/** An application resource that activates when the owning application starts. */
interface ApplicationStartableResource : AutoCloseable {
    fun start()
}
