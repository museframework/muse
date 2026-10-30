package dev.museframework.plugin.core.runtime

/**
 * Supported Muse runtimes.
 *
 * [configKey] is the value users write in plugin configuration:
 *   <configuration><runtime>spring-boot</runtime></configuration>
 *
 * The companion [fromConfigKey] factory centralises key parsing so there
 * are no loose strings anywhere else in the codebase.
 */
sealed class MuseRuntime {
    abstract val configKey: String

    object None       : MuseRuntime() { override val configKey = "none"        }
    object SpringBoot : MuseRuntime() { override val configKey = "spring-boot" }
    object Quarkus    : MuseRuntime() { override val configKey = "quarkus"     }
    object Ktor       : MuseRuntime() { override val configKey = "ktor"        }

    companion object {
        private val all by lazy { listOf(SpringBoot, Quarkus, Ktor) }

        fun fromConfigKey(key: String): MuseRuntime =
            all.firstOrNull { it.configKey == key.trim().lowercase() }
                ?: throw MuseRuntimeException(
                    "Unknown runtime '${key.trim()}'. " +
                    "Valid values: ${all.map { it.configKey }}"
                )

        fun all(): List<MuseRuntime> = all
    }
}
