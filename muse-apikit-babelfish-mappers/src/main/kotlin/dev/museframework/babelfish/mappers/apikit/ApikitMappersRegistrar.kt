package dev.museframework.babelfish.mappers.apikit

import dev.museframework.apikit.api.config.ApikitConfig
import dev.museframework.apikit.api.config.ApikitRouter
import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.babelfish.strategy.CodeGenerationStrategy

object ApikitMappersRegistrar {
    fun register(registry: BabelfishRegistry) {
        // Register apikit:config
        registry.registerConfig("apikit", "config", ApikitConfigMapper)

        // Register apikit:router
        registry.registerElement("apikit", "router", ApikitRouterMapper)

        // TODO: Register apikit:console mapper (optional - runtime not implemented)
    }

    fun registerEmitters(emitter: KotlinCodeEmitter) {
        // Register apikit:config emitter
        emitter.registerConfigEmitter(ApikitConfig::class, ApikitConfigCodeEmitter)

        // Register apikit:router emitter
        emitter.registerElementEmitter(ApikitRouter::class, ApikitRouterCodeEmitter)

        // TODO: Register apikit:console emitter (optional)
    }

    fun registerKotlinPoetEmitters(strategy: CodeGenerationStrategy) {
        // Register apikit:config KotlinPoet emitter
        strategy.registerEmitter(ApikitConfig::class, KotlinPoetApikitConfigCodeEmitter)

        // Register apikit:router KotlinPoet emitter
        strategy.registerEmitter(ApikitRouter::class, KotlinPoetApikitRouterCodeEmitter)
    }
}
