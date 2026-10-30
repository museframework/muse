package dev.museframework.engine.properties

import dev.museframework.dsl.api.model.config.ConfigurationProperties
import dev.museframework.dsl.api.model.config.EnvironmentProperties
import dev.museframework.dsl.api.model.config.GlobalProperty
import dev.museframework.dsl.api.model.config.JvmSystemProperties
import dev.museframework.dsl.validation.ValidationError
import dev.museframework.engine.api.spi.ConfigBuilder
import dev.museframework.engine.app.MuseBootstrapException
import dev.museframework.engine.context.MuseContext
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import java.util.*

/**
 * Property resolution system inspired by Spring Boot.
 *
 * Configurations are applied in bootstrap declaration order. A later value
 * replaces an earlier value whose name is equivalent under relaxed binding.
 *
 * Naming convention binding:
 * - db.host, DB_HOST, db-host, db_host are all equivalent
 * - Normalization: remove non-alphanumeric chars, lowercase, compare
 */
object PropertiesLoader {
    private val logger = LoggerFactory.getLogger(PropertiesLoader::class.java)

    val globalPropertyBuilder =
        ConfigBuilder<GlobalProperty> { config, context ->
            apply(config, context.properties)
        }

    val configurationPropertiesBuilder =
        ConfigBuilder<ConfigurationProperties> { config, context ->
            apply(config, context.properties)
        }

    val configurationPropertiesConfigBuilder =
        ConfigBuilder<dev.museframework.dsl.api.config.ConfigurationPropertiesConfig> { config, context ->
            apply(config, context.properties)
        }

    val environmentPropertiesBuilder =
        ConfigBuilder<EnvironmentProperties> { config, context ->
            apply(config, context.properties)
        }

    val jvmSystemPropertiesBuilder =
        ConfigBuilder<JvmSystemProperties> { config, context ->
            apply(config, context.properties)
        }

    fun apply(
        config: GlobalProperty,
        properties: Properties,
    ) {
        val resolved = resolvePlaceholders(config.value, properties)
        replaceRelaxedProperty(properties, resolved, config.name)
        logger.debug("Set property [{}] = [{}] (from GlobalProperty)", config.name, resolved)
    }

    fun apply(
        config: ConfigurationProperties,
        properties: Properties,
    ) {
        loadProperties(config.file, config.encoding ?: "UTF-8", properties)
    }

    fun apply(
        config: dev.museframework.dsl.api.config.ConfigurationPropertiesConfig,
        properties: Properties,
    ) {
        loadProperties(config.file, "UTF-8", properties)
    }

    fun apply(
        config: EnvironmentProperties,
        target: Properties,
    ) = apply(config, target, System.getenv())

    internal fun apply(
        config: EnvironmentProperties,
        target: Properties,
        environment: Map<String, String>,
    ) {
        var loadedCount = 0

        environment.forEach { (key, value) ->
            if (config.prefix.isEmpty() || key.startsWith(config.prefix)) {
                val propertyKey =
                    if (config.removePrefix && config.prefix.isNotEmpty()) {
                        key.removePrefix(config.prefix)
                    } else {
                        key
                    }

                // Store both the original env var name and the normalized property name
                val normalizedKey = normalizePropertyName(propertyKey)
                replaceRelaxedProperty(target, value, propertyKey, normalizedKey)

                loadedCount++
                logger.debug("Loaded env var [{}] as property [{}] (normalized: [{}])", key, propertyKey, normalizedKey)
            }
        }

        logger.info(
            "Loaded {} environment variables{}",
            loadedCount,
            if (config.prefix.isNotEmpty()) " with prefix '${config.prefix}'" else "",
        )
    }

    fun apply(
        config: JvmSystemProperties,
        target: Properties,
    ) {
        val jvmProps = System.getProperties()
        var loadedCount = 0

        jvmProps.forEach { key, value ->
            val keyStr = key.toString()
            if (config.prefix.isEmpty() || keyStr.startsWith(config.prefix)) {
                val propertyKey =
                    if (config.removePrefix && config.prefix.isNotEmpty()) {
                        keyStr.removePrefix(config.prefix)
                    } else {
                        keyStr
                    }

                replaceRelaxedProperty(target, value.toString(), propertyKey)
                loadedCount++
                logger.debug("Loaded JVM property [{}] = [{}]", propertyKey, value)
            }
        }

        logger.info(
            "Loaded {} JVM system properties{}",
            loadedCount,
            if (config.prefix.isNotEmpty()) " with prefix '${config.prefix}'" else "",
        )
    }

    /**
     * Spring Boot-style property name normalization.
     * Converts various naming conventions to a canonical form.
     *
     * Examples:
     * - db.host → dbhost
     * - DB_HOST → dbhost
     * - db-host → dbhost
     * - db_host → dbhost
     * - dbHost → dbhost (camelCase)
     */
    fun normalizePropertyName(name: String): String =
        name
            .lowercase()
            .replace(".", "")
            .replace("_", "")
            .replace("-", "")

    fun resolveProperty(
        name: String,
        properties: Properties,
    ): String? {
        properties.getProperty(name)?.let { return it }

        val normalizedName = normalizePropertyName(name)
        for ((key, value) in properties) {
            if (normalizePropertyName(key.toString()) == normalizedName) {
                return value.toString()
            }
        }

        return null
    }

    private fun loadProperties(
        file: String,
        encoding: String,
        target: Properties,
    ) {
        val resolvedFile =
            when {
                file.startsWith("classpath:") || file.startsWith("file:") -> file
                else -> "classpath:$file"
            }

        logger.info("Loading properties from {}", resolvedFile.removePrefix("classpath:").removePrefix("file:"))

        val stream =
            when {
                resolvedFile.startsWith("classpath:") -> {
                    val path = resolvedFile.removePrefix("classpath:")
                    Thread.currentThread().contextClassLoader.getResourceAsStream(path)
                        ?: throw MuseBootstrapException(
                            listOf(ValidationError("File not found on classpath: $path")),
                        )
                }

                resolvedFile.startsWith("file:") -> {
                    val path = resolvedFile.removePrefix("file:")
                    val f = java.io.File(path)
                    if (!f.exists()) {
                        throw MuseBootstrapException(
                            listOf(ValidationError("File not found: $path")),
                        )
                    }
                    f.inputStream()
                }

                else -> {
                    throw MuseBootstrapException(
                        listOf(
                            ValidationError(
                                "Unsupported resource prefix in '$file' — use 'classpath:' or 'file:'",
                            ),
                        ),
                    )
                }
            }

        stream.use { input ->
            try {
                val loaded = Properties()
                val reader = input.bufferedReader(charset(encoding))
                when {
                    file.endsWith(".yaml") || file.endsWith(".yml") -> {
                        loadYaml(reader, loaded)
                    }

                    else -> {
                        loaded.load(reader)
                    }
                }

                var loadedCount = 0
                loaded.forEach { k, v ->
                    val key = k.toString()
                    val resolved = resolvePlaceholders(v.toString(), target)
                    replaceRelaxedProperty(target, resolved, key)
                    loadedCount++
                    logger.debug("Loaded property [{}] = [{}] from {}", key, resolved, file)
                }

                logger.info("Loaded {} properties from {}", loadedCount, file)
            } catch (e: MuseBootstrapException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Failed to load properties from '{}': {} — skipping", file, e.message)
            }
        }
    }

    fun resolvePlaceholders(
        value: String,
        properties: Properties,
    ): String {
        val regex = Regex("""\$\{([^}]+)}""")
        return regex.replace(value) { match ->
            val key = match.groupValues[1]
            // Try exact match first, then normalized
            resolveProperty(key, properties) ?: match.value
        }
    }

    private fun replaceRelaxedProperty(
        target: Properties,
        value: String,
        vararg names: String,
    ) {
        require(names.isNotEmpty()) { "At least one property name is required" }

        val normalizedName = normalizePropertyName(names.first())
        target
            .stringPropertyNames()
            .filter { normalizePropertyName(it) == normalizedName }
            .forEach(target::remove)

        names.distinct().forEach { target.setProperty(it, value) }
    }

    private fun loadYaml(
        reader: java.io.BufferedReader,
        target: Properties,
    ) {
        val yaml = Yaml()

        @Suppress("UNCHECKED_CAST")
        val map = yaml.load(reader) as? Map<String, Any> ?: return
        flattenYaml("", map, target)
    }

    private fun flattenYaml(
        prefix: String,
        map: Map<String, Any>,
        target: Properties,
    ) {
        map.forEach { (key, value) ->
            val fullKey = if (prefix.isEmpty()) key else "$prefix.$key"
            when (value) {
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    flattenYaml(fullKey, value as Map<String, Any>, target)
                }

                else -> {
                    target.setProperty(fullKey, value.toString())
                }
            }
        }
    }
}
