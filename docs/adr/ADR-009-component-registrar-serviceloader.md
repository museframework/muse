# ADR-009 — MuseComponentRegistrar Discovery via ServiceLoader

## Status
Accepted

## Context
Muse needs to support both built-in components and custom components from users. We needed a discovery mechanism that works in both standalone and Spring Boot environments.

## Decision
Built-in components are registered automatically via Java `ServiceLoader`.
`muse-engine` provides `META-INF/services/dev.museframework.engine.factory.MuseComponentRegistrar`
pointing to `MuseEngineRegistrar`.

Users register custom components by defining a `@Component MuseComponentRegistrar` bean.
`MuseAutoConfiguration` applies ServiceLoader registrars first, then Spring bean registrars.

## Consequences

**Positive:**
- Zero-configuration component discovery
- Works in standalone and Spring Boot modes
- User components integrate seamlessly
- Standard Java mechanism (no custom code needed)

**Negative:**
- Requires proper `META-INF/services/` file
- No explicit control over registration order

## Implementation Details

### ServiceLoader Configuration
File: `muse-engine/src/main/resources/META-INF/services/dev.museframework.engine.factory.MuseComponentRegistrar`
```
dev.museframework.engine.factory.MuseEngineRegistrar
```

### Registrar Interface
```kotlin
interface MuseComponentRegistrar {
    fun register(factory: MuseComponentFactory)
}
```

### Built-in Registrar
```kotlin
class MuseEngineRegistrar : MuseComponentRegistrar {
    override fun register(factory: MuseComponentFactory) {
        // Processors
        factory.registerProcessor(LoggerConfig::class) { config, context ->
            LoggerMuseProcessor(config, context.expressionEvaluator)
        }
        
        // Routers
        factory.registerRouter(ChoiceConfig::class) { config, context ->
            ChoiceMuseProcessor(config, context.expressionEvaluator)
        }
        
        // Sources
        factory.registerSource(FixedSchedulerConfig::class) { config, context, evaluator ->
            FixedSchedulerSource(config, evaluator)
        }
    }
}
```

### User Custom Registrar
```kotlin
@Component
class MyCustomRegistrar : MuseComponentRegistrar {
    override fun register(factory: MuseComponentFactory) {
        factory.registerProcessor(MyCustomConfig::class) { config, context ->
            MyCustomProcessor(config)
        }
    }
}
```

### Auto-Configuration
```kotlin
@Configuration
class MuseAutoConfiguration {
    @Bean
    fun museComponentFactory(
        serviceLoaderRegistrars: List<MuseComponentRegistrar>,
        springBeanRegistrars: List<MuseComponentRegistrar>
    ): MuseComponentFactory {
        val factory = MuseComponentFactoryImpl()
        
        // Apply ServiceLoader registrars first
        serviceLoaderRegistrars.forEach { it.register(factory) }
        
        // Then Spring bean registrars (allowing overrides)
        springBeanRegistrars.forEach { it.register(factory) }
        
        return factory
    }
}
```

## References
- `muse-engine/src/main/kotlin/dev/museframework/engine/factory/MuseComponentRegistrar.kt`
- `muse-engine/src/main/kotlin/dev/museframework/engine/factory/MuseEngineRegistrar.kt`
- `muse-spring-boot/src/main/kotlin/dev/museframework/springboot/MuseAutoConfiguration.kt`
