package dev.museframework.e2e.composition

import dev.museframework.engine.api.spi.MuseComponentFactory
import dev.museframework.engine.api.spi.MuseComponentRegistrar

class CompositionProbeRegistrar : MuseComponentRegistrar {
    override fun register(factory: MuseComponentFactory) {
        factory.registerProcessor(CompositionProbeConfig::class, compositionProbeProcessorBuilder)
    }
}
