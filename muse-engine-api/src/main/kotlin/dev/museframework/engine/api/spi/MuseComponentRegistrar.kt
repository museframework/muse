package dev.museframework.engine.api.spi

interface MuseComponentRegistrar {
    fun register(factory: MuseComponentFactory)
}
