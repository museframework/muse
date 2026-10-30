package dev.museframework.engine.factory

import dev.museframework.dsl.api.config.*
import dev.museframework.dsl.api.model.flow.FlowRefConfig
import dev.museframework.dsl.api.model.config.ConfigurationProperties
import dev.museframework.dsl.api.model.config.GlobalProperty
import dev.museframework.engine.api.spi.MuseComponentFactory
import dev.museframework.engine.api.spi.MuseComponentRegistrar
import dev.museframework.engine.components.processor.*
import dev.museframework.engine.components.processor.customCodeProcessorBuilder
import dev.museframework.engine.components.source.schedulerSourceBuilder
import dev.museframework.engine.execution.policy.choicePolicyDeclaration
import dev.museframework.engine.execution.policy.flowRefPolicyDeclaration
import dev.museframework.engine.execution.policy.forEachPolicyDeclaration
import dev.museframework.engine.execution.policy.parallelForEachPolicyDeclaration
import dev.museframework.engine.execution.policy.scatterGatherPolicyDeclaration
import dev.museframework.engine.execution.policy.tryPolicyDeclaration
import dev.museframework.engine.execution.policy.untilSuccessfulPolicyDeclaration
import dev.museframework.engine.execution.policy.withCorrelationIdPolicyDeclaration
import dev.museframework.engine.execution.policy.asyncPolicyDeclaration
import dev.museframework.engine.properties.PropertiesLoader.configurationPropertiesBuilder
import dev.museframework.engine.properties.PropertiesLoader.configurationPropertiesConfigBuilder
import dev.museframework.engine.properties.PropertiesLoader.globalPropertyBuilder
import dev.museframework.dsl.api.config.SchedulerConfig

/**
 * Standard engine declarations.
 *
 * Every standard router is a managed policy: it binds prepared occurrences at bootstrap and links
 * its bodies at init instead of compiling pipelines while the context is still partial.
 */
class MuseEngineRegistrar : MuseComponentRegistrar {
    override fun register(factory: MuseComponentFactory) {
        factory.registerSource(SchedulerConfig::class, schedulerSourceBuilder)

        factory.registerProcessor(LoggerConfig::class, loggerProcessorBuilder)
        factory.registerProcessor(TransformConfig::class, transformProcessorBuilder)
        factory.registerProcessor(SetVariableConfig::class, setVariableProcessorBuilder)
        factory.registerProcessor(SetPayloadConfig::class, setPayloadProcessorBuilder)
        factory.registerProcessor(RaiseErrorConfig::class, raiseErrorProcessorBuilder)
        factory.registerProcessor(RemoveVariableConfig::class, removeVariableProcessorBuilder)
        factory.registerProcessor(CustomCodeConfig::class, customCodeProcessorBuilder)

        factory.registerPolicyRouter(FlowRefConfig::class, flowRefPolicyDeclaration)
        factory.registerPolicyRouter(ChoiceConfig::class, choicePolicyDeclaration)
        factory.registerPolicyRouter(TryConfig::class, tryPolicyDeclaration)
        factory.registerPolicyRouter(ForEachConfig::class, forEachPolicyDeclaration)
        factory.registerPolicyRouter(AsyncConfig::class, asyncPolicyDeclaration)
        factory.registerPolicyRouter(ScatterGatherConfig::class, scatterGatherPolicyDeclaration)
        factory.registerPolicyRouter(UntilSuccessfulConfig::class, untilSuccessfulPolicyDeclaration)
        factory.registerPolicyRouter(ParallelForEachConfig::class, parallelForEachPolicyDeclaration)
        factory.registerPolicyRouter(WithCorrelationIdConfig::class, withCorrelationIdPolicyDeclaration)

        // configs
        factory.registerConfig(GlobalProperty::class, globalPropertyBuilder)
        factory.registerConfig(ConfigurationProperties::class, configurationPropertiesBuilder)
        factory.registerConfig(ConfigurationPropertiesConfig::class, configurationPropertiesConfigBuilder)
    }
}
