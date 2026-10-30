# Muse Configuration Guide

Muse supports two equivalent ways to define your application configuration. Both approaches produce identical results —
choose based on your deployment target.

---

## Option 1: Declarative (JVM Classic)

Extend `MuseConfigDefinition` and declare flows, sub-flows, and configurations as properties.
Muse uses reflection at bootstrap time to collect them automatically — **zero runtime overhead**.

```kotlin
@MuseConfig
@Component
class MyMuseConfig : MuseConfigDefinition() {

    val mySubFlow = subFlow("my-sub-flow") {
        docName = "My Sub Flow"
        transform {
            setVariable(
                "myVar", DwExpression.Inline(
                    """
                %dw 2.0
                output application/java
                ---
                "hello"
            """.trimIndent()
                )
            )
        }
    }

    val myFlow = flow("my-flow") {
        docName = "My Flow"
        fixedScheduler {
            docName = "Scheduler"
            intervalMs = 1000
        }
        logger {
            docName = "Logger"
            message = "Received: #[payload]"
        }
        flowRef(mySubFlow) {
            docName = "Sub Flow Reference"
        }
    }

    val globalProperty = GlobalProperty(
        docName = "My Property",
        docId = "prop-001",
        name = "myProp",
        value = "myValue",
    )
}
```

**When to use:**

- JVM deployments (standard Spring Boot microservice)
- You want minimal boilerplate
- GraalVM native image is not required

---

## Option 2: Explicit (JVM + GraalVM Native Image)

Implement `MuseConfigProvider` and return flows and configurations explicitly.
No reflection involved — fully compatible with GraalVM native image.

```kotlin
@MuseConfig
@Component
class MyMuseConfig : MuseConfigProvider {

    private val mySubFlow = subFlow("my-sub-flow") {
        docName = "My Sub Flow"
        transform {
            setVariable(
                "myVar", DwExpression.Inline(
                    """
                %dw 2.0
                output application/java
                ---
                "hello"
            """.trimIndent()
                )
            )
        }
    }

    override fun flows() = listOf(
        flow("my-flow") {
            docName = "My Flow"
            fixedScheduler {
                docName = "Scheduler"
                intervalMs = 1000
            }
            logger {
                docName = "Logger"
                message = "Received: #[payload]"
            }
            flowRef(mySubFlow) {
                docName = "Sub Flow Reference"
            }
        },
        mySubFlow,
    )

    override fun configs() = listOf(
        GlobalProperty(
            docName = "My Property",
            docId = "prop-001",
            name = "myProp",
            value = "myValue",
        )
    )
}
```

**When to use:**

- GraalVM native image deployments
- You prefer explicit configuration over convention
- You need full control over what is registered

---

## Comparison

|                      | `MuseConfigDefinition` | `MuseConfigProvider` |
|----------------------|------------------------|----------------------|
| Boilerplate          | Minimal                | More explicit        |
| Reflection           | Yes (bootstrap only)   | No                   |
| GraalVM Native Image | ❌                      | ✅                    |
| Runtime overhead     | None                   | None                 |
| Spring Boot JVM      | ✅                      | ✅                    |

---

## Component Registration

Regardless of the configuration approach, components (sources, processors, routers) are registered via
`MuseComponentRegistrar`. Built-in components are registered automatically by `muse-spring-boot`.

To register custom components, define a `MuseComponentRegistrar` bean:

```kotlin
@Component
class MyComponentRegistrar : MuseComponentRegistrar {
    override fun register(factory: MuseComponentFactory) {
        factory.registerProcessor(MyProcessorConfig::class, myProcessorBuilder)
        factory.registerSource(MySourceConfig::class, mySourceBuilder)
    }
}
```

---

## Notes

- Both `@MuseConfig` approaches can coexist in the same application.
- Reflection is used **only at bootstrap** in `MuseConfigDefinition` — there is no performance impact at runtime.
- Flow references between configurations defined in different `@MuseConfig` classes are fully supported, as all flows
  are collected before bootstrap runs.
