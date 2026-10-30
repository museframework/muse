# ADR-011 — MuseApplication Entry Point

## Status
Accepted

## Context
Muse applications need a clear entry point that sets up Spring Boot correctly while providing Muse-specific configuration and branding.

## Decision
```kotlin
@MuseApplication(
    name = "my-app",           // optional, defaults to Maven artifactId from build-info.properties
    defaultProfiles = ["dev"], // optional
    webEnvironment = WebApplicationType.NONE,  // default
)
class MyApp

fun main(args: Array<String>) = runMuseApplication<MyApp>(args)
```

`@MuseApplication` is a meta-annotation over `@SpringBootApplication`.
`runMuseApplication` reads `build-info.properties` for `name` and `version`, prints the Muse banner,
suppresses the Spring Boot banner, and starts the application.

## Consequences

**Positive:**
- Single annotation for Muse + Spring Boot configuration
- Automatic build info integration
- Clean, branded startup experience
- Type-safe entry point function

**Negative:**
- Additional abstraction over Spring Boot
- Must understand what @MuseApplication includes

## Implementation Details

### MuseApplication Annotation
```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootApplication  // ← meta-annotation
annotation class MuseApplication(
    val name: String = "",
    val defaultProfiles: Array<String> = [],
    val webEnvironment: WebApplicationType = WebApplicationType.NONE
)
```

### runMuseApplication Function
```kotlin
inline fun <reified T : Any> runMuseApplication(args: Array<String>): ConfigurableApplicationContext {
    // Read build info
    val buildInfo = loadBuildInfo()
    val appName = buildInfo.name ?: T::class.simpleName ?: "muse-app"
    
    // Print Muse banner
    println("""
        ╔══════════════════════════════════════════════════╗
        ║                                                  ║
        ║     ███╗   ███╗██╗   ██╗███████╗███████╗██╗  ██╗ ║
        ║     ████╗ ████║██║   ██║██╔════╝██╔════╝██║ ██╔╝ ║
        ║     ██╔████╔██║██║   ██║███████╗█████╗  █████╔╝  ║
        ║     ██║╚██╔╝██║██║   ██║╚════██║██╔══╝  ██╔═██╗  ║
        ║     ██║ ╚═╝ ██║╚██████╔╝███████║███████╗██║  ██╗ ║
        ║     ╚═╝     ╚═╝ ╚═════╝ ╚══════╝╚══════╝╚═╝  ╚═╝ ║
        ║                                                  ║
        ║     $appName v${buildInfo.version ?: "unknown"}
        ╚══════════════════════════════════════════════════╝
    """.trimIndent())
    
    // Suppress Spring banner
    System.setProperty("spring.main.banner-mode", "off")
    
    // Start application
    return SpringApplication.run(T::class.java, *args)
}
```

## References
- `muse-spring-boot/src/main/kotlin/dev/museframework/springboot/annotation/MuseApplication.kt`
- `muse-spring-boot/src/main/kotlin/dev/museframework/springboot/runMuseApplication.kt`
