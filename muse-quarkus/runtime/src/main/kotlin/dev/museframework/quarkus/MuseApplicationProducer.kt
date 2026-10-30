package dev.museframework.quarkus

import dev.museframework.dsl.api.config.MuseConfigDefinition
import dev.museframework.dsl.api.model.config.MuseConfiguration
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.engine.app.MuseApplication
import dev.museframework.engine.app.MuseApplicationConfig
import dev.museframework.engine.app.bootstrap
import dev.museframework.engine.factory.MuseComponentFactory
import io.quarkus.arc.DefaultBean
import jakarta.enterprise.inject.Instance
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import org.jboss.logging.Logger
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

@Singleton
class MuseApplicationProducer {
    private val logger = Logger.getLogger(MuseApplicationProducer::class.java)

    @Produces
    @Singleton
    @DefaultBean
    fun createApplication(
        definitions: Instance<MuseConfigDefinition>,
        providers: Instance<MuseConfigProvider>,
        factory: MuseComponentFactory,
    ): MuseApplication {
        val flows = mutableListOf<MuseFlow>()
        val configs = mutableListOf<MuseConfiguration>()

        definitions.forEach { definition ->
            definition::class.memberProperties.forEach { property ->
                property.isAccessible = true
                when (val value = property.getter.call(definition)) {
                    is MuseFlow -> flows.add(value)
                    is MuseConfiguration -> configs.add(value)
                }
            }
        }
        providers.forEach { provider ->
            flows.addAll(provider.flows())
            configs.addAll(provider.configs())
        }

        logger.info("Muse bootstrap — found ${flows.size} flows and ${configs.size} configurations")
        return bootstrap(
            MuseApplicationConfig(
                flows = flows,
                configs = configs,
                factory = factory,
            ),
        )
    }
}
