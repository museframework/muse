package dev.museframework.babelfish.mappers.core

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.dsl.api.config.AsyncConfig
import dev.museframework.dsl.api.config.ChoiceConfig
import dev.museframework.dsl.api.config.ConfigurationPropertiesConfig
import dev.museframework.dsl.api.config.ForEachConfig
import dev.museframework.dsl.api.config.LoggerConfig
import dev.museframework.dsl.api.config.ParallelForEachConfig
import dev.museframework.dsl.api.config.RaiseErrorConfig
import dev.museframework.dsl.api.config.RemoveVariableConfig
import dev.museframework.dsl.api.config.ScatterGatherConfig
import dev.museframework.dsl.api.config.SetPayloadConfig
import dev.museframework.dsl.api.config.SetVariableConfig
import dev.museframework.dsl.api.config.TransformConfig
import dev.museframework.dsl.api.config.TryConfig
import dev.museframework.dsl.api.config.UntilSuccessfulConfig
import dev.museframework.dsl.api.config.WithCorrelationIdConfig
import dev.museframework.dsl.api.model.flow.FlowRefConfig
import dev.museframework.dsl.api.config.SchedulerConfig
import dev.museframework.babelfish.mappers.core.SchedulerSourceMapper

object CoreMappersRegistrar {
    fun register(registry: BabelfishRegistry) {
        // Leaf processors
        registry.registerElement("", "set-payload", SetPayloadMapper)
        registry.registerElement("", "set-variable", SetVariableMapper)
        registry.registerElement("", "logger", LoggerMapper)
        registry.registerElement("", "flow-ref", FlowRefMapper)
        registry.registerElement("", "raise-error", RaiseErrorMapper)
        registry.registerElement("", "remove-variable", RemoveVariableMapper)

        // Routers
        registry.registerElement("", "choice", ChoiceMapper)
        registry.registerElement("", "foreach", ForEachMapper)
        registry.registerElement("", "parallel-foreach", ParallelForEachMapper)
        registry.registerElement("", "scatter-gather", ScatterGatherMapper)
        registry.registerElement("", "try", TryMapper)
        registry.registerElement("", "async", AsyncMapper)
        registry.registerElement("", "until-successful", UntilSuccessfulMapper)
        registry.registerElement("", "with-correlation-id", WithCorrelationIdMapper)

        // DataWeave Transform
        registry.registerElement("ee", "transform", TransformMapper)

        // Configuration Properties
        registry.registerConfig("", "configuration-properties", ConfigurationPropertiesMapper)

        // Sources — emitter registered separately in registerEmitters
        registry.registerSource("", "scheduler", SchedulerSourceMapper)
    }

    fun registerEmitters(
        emitter: KotlinCodeEmitter,
        nativeBuild: Boolean = false,
    ) {
        // Leaf processor emitters
        emitter.registerElementEmitter(SetPayloadConfig::class, SetPayloadCodeEmitter)
        emitter.registerElementEmitter(SetVariableConfig::class, SetVariableCodeEmitter)
        emitter.registerElementEmitter(LoggerConfig::class, LoggerCodeEmitter)
        emitter.registerElementEmitter(FlowRefConfig::class, FlowRefCodeEmitter)
        emitter.registerElementEmitter(RaiseErrorConfig::class, RaiseErrorCodeEmitter)
        emitter.registerElementEmitter(RemoveVariableConfig::class, RemoveVariableCodeEmitter)

        // Router emitters
        emitter.registerElementEmitter(ChoiceConfig::class, ChoiceCodeEmitter)
        emitter.registerElementEmitter(ForEachConfig::class, ForEachCodeEmitter)
        emitter.registerElementEmitter(ParallelForEachConfig::class, ParallelForEachCodeEmitter)
        emitter.registerElementEmitter(ScatterGatherConfig::class, ScatterGatherCodeEmitter)
        emitter.registerElementEmitter(TryConfig::class, TryCodeEmitter)
        emitter.registerElementEmitter(AsyncConfig::class, AsyncCodeEmitter)
        emitter.registerElementEmitter(UntilSuccessfulConfig::class, UntilSuccessfulCodeEmitter)
        emitter.registerElementEmitter(WithCorrelationIdConfig::class, WithCorrelationIdCodeEmitter)

        // DataWeave Transform
        emitter.registerElementEmitter(TransformConfig::class, TransformCodeEmitter)

        // Configuration Properties
        emitter.registerConfigEmitter(ConfigurationPropertiesConfig::class, ConfigurationPropertiesCodeEmitter)

        // Sources
        emitter.registerSourceEmitter(SchedulerConfig::class, KotlinPoetSchedulerSourceCodeEmitter)
    }
}
