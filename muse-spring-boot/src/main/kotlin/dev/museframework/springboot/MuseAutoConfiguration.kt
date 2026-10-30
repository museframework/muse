package dev.museframework.springboot

import dev.museframework.dsl.api.config.MuseConfigDefinition
import dev.museframework.dsl.api.model.config.MuseConfiguration
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.engine.api.spi.MuseComponentRegistrar
import dev.museframework.engine.app.MuseApplication
import dev.museframework.engine.app.MuseApplicationConfig
import dev.museframework.engine.app.bootstrap
import dev.museframework.engine.dispatcher.CoroutineDispatcherProvider
import dev.museframework.engine.factory.MuseComponentFactory
import dev.museframework.engine.factory.MuseEngineRegistrar
import dev.museframework.springboot.infrastructure.MuseInfrastructureHandler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.ServiceLoader

@Configuration
@AutoConfiguration
class MuseAutoConfiguration {
    private val logger = org.slf4j.LoggerFactory.getLogger(MuseAutoConfiguration::class.java)

    @Bean
    fun museComponentFactory(springRegistrars: List<MuseComponentRegistrar>): MuseComponentFactory {
        val factory = MuseComponentFactory()

        ServiceLoader
            .load(MuseComponentRegistrar::class.java)
            .forEach { it.register(factory) }

        springRegistrars.forEach { it.register(factory) }

        return factory
    }

    @Bean
    fun museApplication(
        configDefinitions: List<MuseConfigDefinition>,
        configProviders: List<MuseConfigProvider>,
        factory: MuseComponentFactory,
    ): MuseApplication {
        logger.info("Muse bootstrap — found ${configDefinitions.size} definitions, ${configProviders.size} providers")

        val allFlows = mutableListOf<MuseFlow>()
        val allConfigs = mutableListOf<MuseConfiguration>()

        configDefinitions.forEach { definition ->
            val (flows, configs) = collectFromConfigDefinition(definition)
            allFlows.addAll(flows)
            allConfigs.addAll(configs)
        }

        configProviders.forEach { provider ->
            allFlows.addAll(provider.flows())
            allConfigs.addAll(provider.configs())
        }

        return bootstrap(
            MuseApplicationConfig(
                flows = allFlows,
                configs = allConfigs,
                factory = factory,
            ),
        )
    }

    @Bean
    fun museLifecycle(
        museApplication: MuseApplication,
    ): MuseLifecycle = MuseLifecycle(
        museApplication
    )

    @Bean
    fun museEngineRegistrar(): MuseEngineRegistrar = MuseEngineRegistrar()

    @Bean
    fun museInfrastructureHandler(applicationContext: ApplicationContext): MuseInfrastructureHandler =
        MuseInfrastructureHandler(applicationContext)
}
