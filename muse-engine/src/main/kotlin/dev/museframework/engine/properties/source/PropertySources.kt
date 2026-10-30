package dev.museframework.engine.properties.source

import java.nio.charset.Charset
import java.util.Properties

/**
 * Simple in-memory property source backed by a Map.
 * Used for GlobalProperty and command-line arguments.
 */
class MapPropertySource(
    override val name: String,
    override val priority: Int,
    override val sourceType: PropertySourceType,
    private val properties: Map<String, String>
) : PropertySource {
    
    override fun getProperty(key: String): String? = properties[key]
    
    override fun getPropertyNames(): Set<String> = properties.keys.toSet()
    
    companion object {
        /**
         * Creates a MapPropertySource from vararg pairs.
         */
        fun of(
            name: String,
            priority: Int,
            sourceType: PropertySourceType,
            vararg pairs: Pair<String, String>
        ): MapPropertySource = MapPropertySource(name, priority, sourceType, mapOf(*pairs))
        
        /**
         * Creates a MapPropertySource from a Properties object.
         */
        fun fromProperties(
            name: String,
            priority: Int,
            sourceType: PropertySourceType,
            properties: Properties
        ): MapPropertySource = MapPropertySource(
            name, priority, sourceType,
            properties.mapKeys { it.key.toString() }.mapValues { it.value.toString() }
        )
    }
}

/**
 * Property source backed by JVM system properties (-D flags).
 */
class JvmSystemPropertySource(
    override val name: String = "jvm-system",
    private val prefix: String = "",
    private val removePrefix: Boolean = true
) : PropertySource {
    
    override val priority: Int = PropertyPriority.JVM_SYSTEM
    override val sourceType = PropertySourceType.JVM_SYSTEM
    
    override fun getProperty(key: String): String? {
        val effectiveKey = if (prefix.isNotEmpty()) "$prefix.$key" else key
        return System.getProperty(effectiveKey)
    }
    
    override fun getPropertyNames(): Set<String> {
        return System.getProperties()
            .keys
            .filterIsInstance<String>()
            .filter { prefix.isEmpty() || it.startsWith(prefix) }
            .map { 
                if (removePrefix && prefix.isNotEmpty()) it.removePrefix("$prefix.") else it 
            }
            .toSet()
    }
}

/**
 * Property source backed by system environment variables.
 * Implements naming convention translation for flexible matching.
 */
class EnvironmentPropertySource(
    override val name: String = "environment",
    private val prefix: String = "",
    private val removePrefix: Boolean = true,
    private val normalizeNames: Boolean = true
) : PropertySource {
    
    override val priority: Int = PropertyPriority.ENVIRONMENT
    override val sourceType = PropertySourceType.ENVIRONMENT
    
    private val normalizedMap: Map<String, String> by lazy { buildNormalizedMap() }
    
    private fun buildNormalizedMap(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        System.getenv().forEach { (key, value) ->
            val effectiveKey = when {
                prefix.isNotEmpty() && key.startsWith(prefix) -> {
                    if (removePrefix) key.removePrefix(prefix) else key
                }
                prefix.isNotEmpty() -> return@forEach
                else -> key
            }
            
            val normalizedKey = if (normalizeNames) {
                normalizeToDotNotation(effectiveKey)
            } else {
                effectiveKey.lowercase()
            }
            result[normalizedKey] = value
        }
        return result
    }
    
    override fun getProperty(key: String): String? = normalizedMap[key.lowercase()]
    
    override fun getPropertyNames(): Set<String> = normalizedMap.keys
    
    private fun normalizeToDotNotation(name: String): String {
        return name
            .replace(Regex("([a-z])([A-Z])")) { 
                "${it.groupValues[1]}.${it.groupValues[2].lowercase()}" 
            }
            .replace("-", ".")
            .replace("_", ".")
            .lowercase()
            .replace(Regex("\\.+"), ".")
            .trim('.')
    }
}

/**
 * Property source backed by a .properties file.
 */
class PropertiesFileSource(
    override val name: String,
    private val filePath: java.nio.file.Path,
    private val encoding: String = "UTF-8"
) : PropertySource {
    
    override val priority: Int = PropertyPriority.APPLICATION_FILE
    override val sourceType = PropertySourceType.APPLICATION_FILE
    
    private val properties: Map<String, String> by lazy { loadProperties() }
    
    private fun loadProperties(): Map<String, String> {
        val props = Properties()
        java.nio.file.Files.newInputStream(filePath).use { 
            props.load(it.reader(Charset.forName(encoding)))
        }
        return props.mapKeys { it.key.toString() }.mapValues { it.value.toString() }
    }
    
    override fun getProperty(key: String): String? = properties[key]
    
    override fun getPropertyNames(): Set<String> = properties.keys.toSet()
}

/**
 * Property source backed by a YAML file.
 * Flattens nested YAML structure to dot-notation keys.
 */
class YamlFileSource(
    override val name: String,
    private val filePath: java.nio.file.Path,
    private val encoding: String = "UTF-8"
) : PropertySource {
    
    override val priority: Int = PropertyPriority.APPLICATION_FILE
    override val sourceType = PropertySourceType.APPLICATION_FILE
    
    private val properties: Map<String, String> by lazy { loadYaml() }
    
    private fun loadYaml(): Map<String, String> {
        val content = java.nio.file.Files.readString(filePath, Charset.forName(encoding))
        return parseYaml(content)
    }
    
    override fun getProperty(key: String): String? = properties[key]
    
    override fun getPropertyNames(): Set<String> = properties.keys.toSet()
    
    private fun parseYaml(content: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val lines = content.lines()
        var currentPrefix = ""
        val indentStack = mutableListOf(0 to "")
        
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            
            val indent = line.takeWhile { it == ' ' }.length
            
            while (indentStack.isNotEmpty() && indent <= indentStack.last().first) {
                indentStack.removeAt(indentStack.lastIndex)
            }
            
            currentPrefix = if (indentStack.isNotEmpty()) indentStack.last().second else ""
            
            when {
                trimmed.contains(": ") -> {
                    val (key, value) = trimmed.split(": ", limit = 2)
                    val fullKey = if (currentPrefix.isNotEmpty()) "$currentPrefix.$key" else key
                    val cleanValue = value.trim().removeSurrounding("\"").removeSurrounding("'")
                    result[fullKey] = cleanValue
                }
                trimmed.endsWith(":") -> {
                    val key = trimmed.removeSuffix(":")
                    val fullKey = if (currentPrefix.isNotEmpty()) "$currentPrefix.$key" else key
                    indentStack.add(indent to fullKey)
                }
            }
        }
        
        return result
    }
}

/**
 * Wrapper that adjusts priority for profile-specific sources.
 */
class ProfilePropertySourceWrapper(
    private val delegate: PropertySource,
    private val profile: String
) : PropertySource by delegate {
    
    override val priority: Int = PropertyPriority.forProfile(delegate.priority)
    override val name: String = "${delegate.name}[$profile]"
    override val sourceType: PropertySourceType = PropertySourceType.PROFILE_SPECIFIC
}