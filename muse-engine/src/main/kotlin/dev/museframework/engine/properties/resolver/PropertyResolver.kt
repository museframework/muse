package dev.museframework.engine.properties.resolver

import dev.museframework.engine.properties.source.*
import java.util.*

/**
 * Central property resolver that aggregates multiple property sources
 * with priority-based resolution.
 */
class PropertyResolver(
    val sources: List<PropertySource>,
    private val placeholderResolver: PlaceholderResolver = PlaceholderResolver.Default
) {
    private val sortedSources: List<PropertySource> = sources.sortedByDescending { it.priority }

    /**
     * Resolves a property by checking all sources in priority order.
     * Returns the first non-null value found, with placeholder resolution.
     */
    fun resolve(key: String): String? {
        for (source in sortedSources) {
            val value = source.getProperty(key)
            if (value != null) {
                return placeholderResolver.resolve(value, this)
            }
        }
        return null
    }

    /**
     * Resolves a property or throws if not found.
     */
    fun resolveRequired(key: String): String =
        resolve(key) ?: throw PropertyNotFoundException(key, sortedSources.map { it.name })

    /**
     * Resolves a property with a default value.
     */
    fun resolveOrDefault(key: String, default: String): String =
        resolve(key) ?: default

    /**
     * Returns all known property names across all sources.
     */
    fun getAllPropertyNames(): Set<String> =
        sortedSources.flatMap { it.getPropertyNames() }.toSet()

    /**
     * Returns the source that provided a property value.
     */
    fun getSourceForProperty(key: String): PropertySource? {
        for (source in sortedSources) {
            if (source.getProperty(key) != null) return source
        }
        return null
    }

    /**
     * Creates a snapshot of all resolved properties.
     */
    fun toMap(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (source in sortedSources.reversed()) {
            for (key in source.getPropertyNames()) {
                source.getProperty(key)?.let { result[key] = it }
            }
        }
        return result.mapValues { (_, value) ->
            placeholderResolver.resolve(value, this) ?: value
        }
    }

    /**
     * Creates a Java Properties object for compatibility.
     */
    fun toProperties(): Properties {
        val props = Properties()
        toMap().forEach { (k, v) -> props.setProperty(k, v) }
        return props
    }

    fun getRawValue(key: String): String? {
        for (source in sortedSources) {
            val value = source.getProperty(key)
            if (value != null) return value  // raw — no placeholder resolution
        }
        return null
    }
}

/**
 * Resolves ${...} placeholders in property values.
 */
interface PlaceholderResolver {
    fun resolve(value: String, resolver: PropertyResolver): String?

    companion object {
        val Default = DefaultPlaceholderResolver()
    }
}

class DefaultPlaceholderResolver(
    private val prefix: String = "\${",
    private val suffix: String = "}",
    private val defaultSeparator: String = ":"
) : PlaceholderResolver {

    private val placeholderRegex = Regex("""\$\{([^}]+)}""")

    override fun resolve(value: String, resolver: PropertyResolver): String? {
        if (!value.contains(prefix)) return value
        return resolveWithCycleDetection(value, resolver, mutableSetOf())
    }

    private fun resolveWithCycleDetection(
        value: String,
        resolver: PropertyResolver,
        resolutionPath: MutableSet<String>
    ): String {
        return placeholderRegex.replace(value) { matchResult ->
            val placeholder = matchResult.groupValues[1]

            val (key, defaultValue) = if (placeholder.contains(defaultSeparator)) {
                val parts = placeholder.split(defaultSeparator, limit = 2)
                parts[0] to parts[1]
            } else {
                placeholder to null
            }

            if (resolutionPath.contains(key)) {
                throw CircularPropertyReferenceException(key, resolutionPath.toList() + key)
            }

            resolutionPath.add(key)

            val rawValue = resolver.getRawValue(key)  // valore grezzo senza risoluzione

            val result = when {
                rawValue != null -> resolveWithCycleDetection(rawValue, resolver, resolutionPath)
                defaultValue != null -> defaultValue
                else -> matchResult.value
            }

            resolutionPath.remove(key)
            result
        }
    }
}

/**
 * Fluent builder for constructing PropertyResolver.
 */
class PropertySourceBuilder {
    private val sources = mutableListOf<PropertySource>()

    fun withCommandLineArgs(args: Map<String, String>): PropertySourceBuilder {
        if (args.isNotEmpty()) {
            sources.add(
                MapPropertySource(
                    "command-line",
                    PropertyPriority.COMMAND_LINE,
                    PropertySourceType.COMMAND_LINE,
                    args
                )
            )
        }
        return this
    }

    fun withJvmProperties(prefix: String = "", removePrefix: Boolean = true): PropertySourceBuilder {
        sources.add(JvmSystemPropertySource("jvm-system", prefix, removePrefix))
        return this
    }

    fun withEnvironment(
        prefix: String = "",
        removePrefix: Boolean = true,
        normalizeNames: Boolean = true
    ): PropertySourceBuilder {
        sources.add(EnvironmentPropertySource("environment", prefix, removePrefix, normalizeNames))
        return this
    }

    fun withPropertiesFile(path: java.nio.file.Path, encoding: String = "UTF-8"): PropertySourceBuilder {
        sources.add(PropertiesFileSource(path.fileName.toString(), path, encoding))
        return this
    }

    fun withYamlFile(path: java.nio.file.Path, encoding: String = "UTF-8"): PropertySourceBuilder {
        sources.add(YamlFileSource(path.fileName.toString(), path, encoding))
        return this
    }

    fun withGlobalProperty(name: String, value: String, priority: Int? = null): PropertySourceBuilder {
        sources.add(
            MapPropertySource(
                "global-$name",
                priority ?: PropertyPriority.DEFAULT,
                PropertySourceType.DEFAULT,
                mapOf(name to value)
            )
        )
        return this
    }

    fun withSource(source: PropertySource): PropertySourceBuilder {
        sources.add(source)
        return this
    }

    fun withProfile(profile: String, vararg profileSources: PropertySource): PropertySourceBuilder {
        profileSources.forEach { source ->
            sources.add(ProfilePropertySourceWrapper(source, profile))
        }
        return this
    }

    fun build(): PropertyResolver = PropertyResolver(sources.toList())
}