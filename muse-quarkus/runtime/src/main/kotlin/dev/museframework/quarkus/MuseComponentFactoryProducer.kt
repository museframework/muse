package dev.museframework.quarkus

import dev.museframework.engine.api.spi.MuseComponentRegistrar
import dev.museframework.engine.factory.MuseComponentFactory
import io.quarkus.arc.DefaultBean
import jakarta.enterprise.inject.Instance
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import org.jboss.logging.Logger
import java.util.ServiceLoader

@Singleton
class MuseComponentFactoryProducer {
    private val logger = Logger.getLogger(MuseComponentFactoryProducer::class.java)

    @Produces
    @Singleton
    @DefaultBean
    fun createComponentFactory(registrars: Instance<MuseComponentRegistrar>): MuseComponentFactory {
        val factory = MuseComponentFactory()

        ServiceLoader.load(MuseComponentRegistrar::class.java).forEach { registrar ->
            logger.debug("Registering Muse components from ${registrar.javaClass.name}")
            registrar.register(factory)
        }
        registrars.forEach { registrar ->
            logger.debug("Registering Quarkus components from ${registrar.javaClass.name}")
            registrar.register(factory)
        }

        return factory
    }
}
