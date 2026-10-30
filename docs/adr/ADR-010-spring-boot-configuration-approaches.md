# ADR-010 — Spring Boot Configuration: Two Approaches

## Status
Accepted

## Context
Muse needs to support both JVM mode (with reflection) and GraalVM native image (without reflection). We needed two configuration approaches with different trade-offs.

## Decision
Two approaches are supported simultaneously:

**Option 1 — `MuseConfigDefinition` (JVM, low boilerplate):**
```kotlin
@MuseConfig
@Component
class MyConfig : MuseConfigDefinition() {
    val myFlow = flow("my-flow") { ... }        // discovered via kotlin-reflect
    val mySubFlow = subFlow("my-sub-flow") { ... }
}
```
Uses Kotlin reflection at bootstrap only. Not compatible with GraalVM native image without additional hints.

**Option 2 — `MuseConfigProvider` (JVM + native image):**
```kotlin
@MuseConfig
@Component
class MyConfig : MuseConfigProvider {
    override fun flows() = listOf(flow("my-flow") { ... })
}
```
No reflection. Fully compatible with GraalVM native image.

Both approaches are supported simultaneously by `MuseAutoConfiguration`.

## Consequences

**Positive:**
- Low boilerplate for JVM users (reflection approach)
- Native image support for GraalVM users (explicit approach)
- Migration path: users can start with reflection and switch later
- Both can coexist in the same application

**Negative:**
- Two patterns to document and maintain
- Users must understand trade-offs
- Reflection approach requires runtime hints for GraalVM

## Implementation Details

### MuseConfigDefinition (Reflection-based)
```kotlin
abstract class MuseConfigDefinition {
    internal fun getFlows(): List<MuseFlow> {
        // Uses kotlin-reflect to find all flow/subFlow properties
        return this::class.memberProperties
            .filter { it.returnType.isSubtypeOf(MuseFlow::class.createType()) }
            .map { it.get(this) as MuseFlow }
    }
}
```

### MuseConfigProvider (Explicit)
```kotlin
interface MuseConfigProvider {
    fun flows(): List<MuseFlow> = emptyList()
    fun configs(): List<MuseConfiguration> = emptyList()
}
```

### Auto-Configuration Support
```kotlin
@Configuration
class MuseAutoConfiguration {
    @Bean
    fun museApplicationConfig(
        definitions: List<MuseConfigDefinition>,
        providers: List<MuseConfigProvider>
    ): MuseApplicationConfig {
        val flowsFromDefinitions = definitions.flatMap { it.getFlows() }
        val flowsFromProviders = providers.flatMap { it.flows() }
        
        return MuseApplicationConfig(
            flows = (flowsFromDefinitions + flowsFromProviders).associateBy { it.name },
            // ...
        )
    }
}
```

### GraalVM Hints (for reflection approach)
```kotlin
class MuseRuntimeHints : RuntimeHintsRegistrar {
    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        hints.reflection().registerType(MuseConfigDefinition::class.java)
        // Register all config classes...
    }
}
```

## References
- `muse-dsl/src/main/kotlin/dev/museframework/dsl/api/config/MuseConfigDefinition.kt`
- `muse-spring-boot/src/main/kotlin/dev/museframework/springboot/MuseConfigProvider.kt`
- `muse-spring-boot/src/main/kotlin/dev/museframework/springboot/MuseAutoConfiguration.kt`
