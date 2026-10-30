# Muse Property Resolution System - Architectural Design

## Executive Summary

This document outlines a comprehensive property resolution system for Muse that supports:
- Multi-source property loading with strict priority ordering
- Environment variable naming convention translation (Spring Boot-style)
- Placeholder resolution with circular reference detection
- Profile-based configuration
- Hot reload capability (optional)
- Encrypted property support

---

## 1. Current State Analysis

### Existing Implementation
```
Bootstrap.kt
    ↓
Properties() (empty java.util.Properties)
    ↓
PropertiesLoader.apply(config, properties) for each config
    ↓
Last-write-wins semantics (no priority enforcement)
```

### Limitations
1. **No priority enforcement** - order of config application determines final value
2. **No env var translation** - `DB_HOST` cannot match `db.host`
3. **Basic placeholder resolution** - no circular reference detection
4. **No profiles** - no `application-{profile}.properties` support
5. **No property change events** - static configuration only

---

## 2. PropertySource Hierarchy Design

### 2.1 Core Abstraction

```kotlin
/**
 * Represents a source of properties with associated priority.
 * Higher priority values take precedence.
 */
interface PropertySource {
    val name: String
    val priority: Int
    val sourceType: PropertySourceType
    
    /**
     * Returns the property value for the given key, or null if not found.
     * The key is already normalized to dot-notation.
     */
    fun getProperty(key: String): String?
    
    /**
     * Returns all property names this source contains.
     * Used for enumeration and debugging.
     */
    fun getPropertyNames(): Set<String>
    
    /**
     * Optional: Support for property change notifications
     */
    fun addChangeListener(listener: PropertyChangeListener): Boolean = false
    fun removeChangeListener(listener: PropertyChangeListener): Boolean = false
}

enum class PropertySourceType {
    COMMAND_LINE,      // Priority 1000
    JVM_SYSTEM,        // Priority 800
    ENVIRONMENT,       // Priority 600
    APPLICATION_FILE,  // Priority 400
    DEFAULT            // Priority 200
}

interface PropertyChangeListener {
    fun onPropertyChange(key: String, oldValue: String?, newValue: String?)
}
```

### 2.2 Priority Constants

```kotlin
object PropertyPriority {
    const val COMMAND_LINE = 1000
    const val JVM_SYSTEM = 800
    const val ENVIRONMENT = 600
    const val APPLICATION_FILE = 400
    const val PROFILE_SPECIFIC = 350    // Slightly lower than base
    const val DEFAULT = 200
    const val FALLBACK = 100
}
```

### 2.3 PropertySource Implementations

```
                    PropertySource (interface)
                           │
        ┌──────────────────┼──────────────────┐
        │                  │                  │
  MapPropertySource   FilePropertySource   SystemPropertySource
        │                  │                  │
        ├─ GlobalProperty  ├─ PropertiesFile  ├─ EnvironmentVariables
        └─ CommandLineArgs  └─ YamlFile        └─ JvmSystemProperties
```

#### MapPropertySource
```kotlin
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
    
    override fun getPropertyNames(): Set<String> = properties.keys
    
    companion object {
        fun of(name: String, priority: Int, sourceType: PropertySourceType, 
               vararg pairs: Pair<String, String>): MapPropertySource {
            return MapPropertySource(name, priority, sourceType, mapOf(*pairs))
        }
    }
}
```

#### EnvironmentPropertySource
```kotlin
/**
 * Property source backed by system environment variables.
 * Implements naming convention translation.
 */
class EnvironmentPropertySource(
    override val name: String = "environment",
    private val prefix: String = "",
    private val removePrefix: Boolean = true,
    private val nameNormalizer: PropertyNameNormalizer = PropertyNameNormalizer.Default
) : PropertySource {
    
    override val priority: Int = PropertyPriority.ENVIRONMENT
    override val sourceType = PropertySourceType.ENVIRONMENT
    
    // Lazy-loaded normalized map for efficient lookup
    private val normalizedMap: Map<String, String> by lazy {
        buildNormalizedMap()
    }
    
    private fun buildNormalizedMap(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        System.getenv().forEach { (key, value) ->
            val effectiveKey = if (prefix.isNotEmpty() && key.startsWith(prefix)) {
                if (removePrefix) key.removePrefix(prefix) else key
            } else if (prefix.isNotEmpty()) {
                return@forEach // Skip non-matching
            } else {
                key
            }
            // Normalize to dot notation for internal representation
            val normalizedKey = nameNormalizer.normalizeToDotNotation(effectiveKey)
            result[normalizedKey] = value
        }
        return result
    }
    
    override fun getProperty(key: String): String? = normalizedMap[key]
    
    override fun getPropertyNames(): Set<String> = normalizedMap.keys
}
```

#### JvmSystemPropertySource
```kotlin
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
        // System properties already use dot notation
        val effectiveKey = if (prefix.isNotEmpty()) "$prefix.$key" else key
        return System.getProperty(effectiveKey)
    }
    
    override fun getPropertyNames(): Set<String> {
        return System.getProperties()
            .keys
            .filterIsInstance<String>()
            .filter { prefix.isEmpty() || it.startsWith(prefix) }
            .map { if (removePrefix && prefix.isNotEmpty()) it.removePrefix("$prefix.") else it }
            .toSet()
    }
}
```

#### FilePropertySource
```kotlin
/**
 * Property source backed by a configuration file.
 * Supports .properties, .yaml, and .yml formats.
 */
sealed class FilePropertySource(
    override val name: String,
    val file: Path,
    val encoding: String = "UTF-8"
) : PropertySource {
    
    override val priority: Int = PropertyPriority.APPLICATION_FILE
    override val sourceType = PropertySourceType.APPLICATION_FILE
    
    protected abstract fun loadProperties(): Map<String, String>
    
    private val properties: Map<String, String> by lazy { loadProperties() }
    
    override fun getProperty(key: String): String? = properties[key]
    
    override fun getPropertyNames(): Set<String> = properties.keys
}

class PropertiesFileSource(
    file: Path,
    encoding: String = "UTF-8",
    name: String = file.fileName.toString()
) : FilePropertySource(name, file, encoding) {
    
    override fun loadProperties(): Map<String, String> {
        val props = java.util.Properties()
        file.inputStream().use { props.load(it) }
        return props.mapKeys { it.key.toString() }.mapValues { it.value.toString() }
    }
}

class YamlFileSource(
    file: Path,
    encoding: String = "UTF-8",
    name: String = file.fileName.toString()
) : FilePropertySource(name, file, encoding) {
    
    override fun loadProperties(): Map<String, String> {
        // Use snakeyaml or similar to flatten YAML to dot-notation
        return YamlPropertyMapper.load(file)
    }
}
```

---

## 3. Naming Convention Normalization Strategy

### 3.1 Design Decision: Normalization Function (not BiMap)

**Rationale:**
- BiMap requires pre-building all possible mappings (memory intensive)
- Normalization function is stateless and O(1) per lookup
- Matches Spring Boot's approach for compatibility

### 3.2 Normalization Algorithm

```kotlin
/**
 * Normalizes property names to a canonical dot-notation form.
 * 
 * Supported input formats:
 * - db.host (dot notation) → db.host
 * - db-host (kebab-case) → db.host  
 * - DB_HOST (upper snake) → db.host
 * - dbHost (camelCase) → db.host
 */
interface PropertyNameNormalizer {
    /**
     * Converts any naming convention to canonical dot-notation lowercase.
     */
    fun normalizeToDotNotation(name: String): String
    
    /**
     * Generates all possible variants of a property name.
     * Used for reverse lookup (what env vars could match this property).
     */
    fun generateVariants(normalizedName: String): Set<String>
}

object PropertyNameNormalizer : PropertyNameNormalizer {
    
    private val CAMEL_CASE_REGEX = Regex("([a-z])([A-Z])")
    private val KEBAB_REGEX = Regex("-")
    private val UNDERSCORE_REGEX = Regex("_")
    private val DOT_REGEX = Regex("\\.")
    
    override fun normalizeToDotNotation(name: String): String {
        return name
            // Handle camelCase: dbHost → db.Host
            .replace(CAMEL_CASE_REGEX) { "${it.groupValues[1]}.${it.groupValues[2].lowercase()}" }
            // Handle kebab-case: db-host → db.host
            .replace(KEBAB_REGEX, ".")
            // Handle snake_case: DB_HOST → DB.HOST
            .replace(UNDERSCORE_REGEX, ".")
            // Normalize to lowercase
            .lowercase()
            // Collapse multiple dots
            .replace(Regex("\\.+"), ".")
            // Trim dots from edges
            .trim('.')
    }
    
    override fun generateVariants(normalizedName: String): Set<String> {
        if (normalizedName.isEmpty()) return emptySet()
        
        val parts = normalizedName.split(".")
        val variants = mutableSetOf<String>()
        
        // Dot notation: db.host
        variants.add(normalizedName)
        
        // Kebab-case: db-host
        variants.add(parts.joinToString("-"))
        
        // Upper snake: DB_HOST
        variants.add(parts.joinToString("_") { it.uppercase() })
        
        // CamelCase: dbHost
        variants.add(parts.mapIndexed { i, part ->
            if (i == 0) part else part.replaceFirstChar { it.uppercase() }
        }.joinToString(""))
        
        return variants
    }
}
```

### 3.3 Environment Variable Matching Strategy

```kotlin
/**
 * Strategy for matching environment variables to property names.
 */
class EnvironmentVariableMatcher(
    private val normalizer: PropertyNameNormalizer = PropertyNameNormalizer
) {
    /**
     * Builds a lookup map from environment variables.
     * Key: normalized property name, Value: original env var value
     */
    fun buildLookupMap(envVars: Map<String, String> = System.getenv()): Map<String, String> {
        return envVars.entries.associate { (envKey, value) ->
            normalizer.normalizeToDotNotation(envKey) to value
        }
    }
    
    /**
     * Finds all environment variables that could match a property name.
     */
    fun findMatchingEnvVars(propertyName: String, envVars: Map<String, String> = System.getenv()): List<Pair<String, String>> {
        val normalized = normalizer.normalizeToDotNotation(propertyName)
        val variants = normalizer.generateVariants(normalized)
        
        return envVars.entries
            .filter { (key, _) ->
                normalizer.normalizeToDotNotation(key) == normalized ||
                variants.contains(key)
            }
            .map { it.key to it.value }
    }
}
```

---

## 4. Property Resolver (Composite)

### 4.1 Core Resolver

```kotlin
/**
 * Central property resolver that aggregates multiple property sources
 * with priority-based resolution.
 */
class PropertyResolver(
    private val sources: List<PropertySource>,
    private val placeholderResolver: PlaceholderResolver = PlaceholderResolver.Default
) {
    
    // Sources sorted by priority (highest first)
    private val sortedSources: List<PropertySource> = sources.sortedByDescending { it.priority }
    
    /**
     * Resolves a property by checking all sources in priority order.
     * Returns the first non-null value found.
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
    fun resolveRequired(key: String): String {
        return resolve(key) ?: throw PropertyNotFoundException(key)
    }
    
    /**
     * Resolves a property with a default value.
     */
    fun resolveOrDefault(key: String, default: String): String {
        return resolve(key) ?: default
    }
    
    /**
     * Returns all known property names across all sources.
     */
    fun getAllPropertyNames(): Set<String> {
        return sortedSources.flatMap { it.getPropertyNames() }.toSet()
    }
    
    /**
     * Returns the source that provided a property value.
     * Useful for debugging and auditing.
     */
    fun getSourceForProperty(key: String): PropertySource? {
        for (source in sortedSources) {
            if (source.getProperty(key) != null) {
                return source
            }
        }
        return null
    }
    
    /**
     * Creates a snapshot of all resolved properties.
     * Used for passing to components that need a simple Map view.
     */
    fun toMap(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        // Apply in reverse order (lowest priority first) so higher priority overwrites
        for (source in sortedSources.reversed()) {
            for (key in source.getPropertyNames()) {
                source.getProperty(key)?.let { result[key] = it }
            }
        }
        // Resolve placeholders in all values
        return result.mapValues { (_, value) ->
            placeholderResolver.resolve(value, this) ?: value
        }
    }
    
    /**
     * Creates a Java Properties object for compatibility.
     */
    fun toProperties(): java.util.Properties {
        val props = java.util.Properties()
        toMap().forEach { (k, v) -> props.setProperty(k, v) }
        return props
    }
}

class PropertyNotFoundException(key: String) : 
    RuntimeException("Property '$key' not found in any property source")
```

### 4.2 Placeholder Resolver

```kotlin
/**
 * Resolves ${...} placeholders in property values.
 */
interface PlaceholderResolver {
    fun resolve(value: String, resolver: PropertyResolver): String?
}

class DefaultPlaceholderResolver(
    private val prefix: String = "\${",
    private val suffix: String = "}",
    private val defaultSeparator: String = ":"
) : PlaceholderResolver {
    
    private val placeholderRegex = Regex("""\$\{([^}]+)}""")
    
    override fun resolve(value: String, resolver: PropertyResolver): String? {
        if (!value.contains(prefix)) return value
        
        // Track resolution path to detect circular references
        return resolveWithCycleDetection(value, resolver, mutableSetOf())
    }
    
    private fun resolveWithCycleDetection(
        value: String, 
        resolver: PropertyResolver,
        resolutionPath: MutableSet<String>
    ): String {
        return placeholderRegex.replace(value) { matchResult ->
            val placeholder = matchResult.groupValues[1]
            
            // Handle default values: ${key:default}
            val (key, defaultValue) = if (placeholder.contains(defaultSeparator)) {
                val parts = placeholder.split(defaultSeparator, limit = 2)
                parts[0] to parts[1]
            } else {
                placeholder to null
            }
            
            // Cycle detection
            if (resolutionPath.contains(key)) {
                throw CircularPropertyReferenceException(
                    key, resolutionPath.toList() + key
                )
            }
            
            resolutionPath.add(key)
            
            val resolved = resolver.resolve(key)
            resolutionPath.remove(key)
            
            when {
                resolved != null -> resolveWithCycleDetection(resolved, resolver, resolutionPath)
                defaultValue != null -> defaultValue
                else -> matchResult.value // Keep unresolved placeholder
            }
        }
    }
    
    companion object : PlaceholderResolver by DefaultPlaceholderResolver()
}

class CircularPropertyReferenceException(
    val property: String,
    val referenceChain: List<String>
) : RuntimeException(
    "Circular property reference detected: ${referenceChain.joinToString(" → ")}"
)
```

---

## 5. Configuration Components Design

### 5.1 Enhanced Configuration Model

```kotlin
// Base interface (existing)
interface MuseConfiguration {
    val name: String?
}

// Enhanced property configurations
sealed interface PropertyConfiguration : MuseConfiguration {
    val priority: Int?
}

/**
 * Explicit single property definition.
 * Priority: JVM_SYSTEM (can be overridden by config)
 */
data class GlobalProperty(
    override val name: String,  // Required for GlobalProperty
    val value: String,
    override val priority: Int? = null  // Allow custom priority
) : PropertyConfiguration {
    override val sourceType = PropertySourceType.DEFAULT
}

/**
 * Load properties from a file.
 * Supports .properties, .yaml, .yml extensions.
 */
data class ConfigurationProperties(
    val file: String,
    val encoding: String = "UTF-8",
    val profile: String? = null,  // NEW: Profile support
    override val priority: Int? = null,
    override val name: String? = null
) : PropertyConfiguration

/**
 * Load properties from environment variables.
 */
data class EnvironmentProperties(
    val prefix: String = "",
    val removePrefix: Boolean = true,
    val normalizeNames: Boolean = true,  // NEW: Enable/disable normalization
    override val priority: Int? = null,
    override val name: String? = null
) : PropertyConfiguration

/**
 * Load properties from JVM system properties.
 */
data class JvmSystemProperties(
    val prefix: String = "",
    val removePrefix: Boolean = true,
    override val priority: Int? = null,
    override val name: String? = null
) : PropertyConfiguration

/**
 * NEW: Profile-specific configuration.
 */
data class ProfileConfiguration(
    val activeProfiles: List<String>,
    val configs: List<PropertyConfiguration>,
    override val name: String? = null
) : MuseConfiguration

/**
 * NEW: Encrypted property configuration.
 */
data class EncryptedProperty(
    override val name: String,
    val encryptedValue: String,
    val encryptionAlgorithm: String = "AES",
    override val priority: Int? = null
) : PropertyConfiguration
```

### 5.2 PropertySourceBuilder (Fluent API)

```kotlin
/**
 * Fluent builder for constructing PropertyResolver.
 */
class PropertySourceBuilder {
    private val sources = mutableListOf<PropertySource>()
    
    fun withCommandLineArgs(args: Map<String, String>): PropertySourceBuilder {
        sources.add(MapPropertySource(
            "command-line",
            PropertyPriority.COMMAND_LINE,
            PropertySourceType.COMMAND_LINE,
            args
        ))
        return this
    }
    
    fun withJvmProperties(prefix: String = "", removePrefix: Boolean = true): PropertySourceBuilder {
        sources.add(JvmSystemPropertySource("jvm-system", prefix, removePrefix))
        return this
    }
    
    fun withEnvironment(prefix: String = "", removePrefix: Boolean = true): PropertySourceBuilder {
        sources.add(EnvironmentPropertySource("environment", prefix, removePrefix))
        return this
    }
    
    fun withPropertiesFile(path: Path, encoding: String = "UTF-8"): PropertySourceBuilder {
        sources.add(PropertiesFileSource(path, encoding))
        return this
    }
    
    fun withYamlFile(path: Path, encoding: String = "UTF-8"): PropertySourceBuilder {
        sources.add(YamlFileSource(path, encoding))
        return this
    }
    
    fun withGlobalProperty(name: String, value: String, priority: Int? = null): PropertySourceBuilder {
        sources.add(MapPropertySource(
            "global-$name",
            priority ?: PropertyPriority.DEFAULT,
            PropertySourceType.DEFAULT,
            mapOf(name to value)
        ))
        return this
    }
    
    fun withProfile(profile: String, vararg profileSources: PropertySource): PropertySourceBuilder {
        profileSources.forEach { source ->
            sources.add(ProfilePropertySourceWrapper(source, profile))
        }
        return this
    }
    
    fun build(): PropertyResolver {
        return PropertyResolver(sources.toList())
    }
}

/**
 * Wrapper that adjusts priority for profile-specific sources.
 */
class ProfilePropertySourceWrapper(
    private val delegate: PropertySource,
    private val profile: String
) : PropertySource by delegate {
    override val priority: Int = delegate.priority - 50  // Lower than base
    override val name: String = "${delegate.name}[$profile]"
}
```

---

## 6. Bootstrap Integration

### 6.1 Enhanced Bootstrap Process

```kotlin
// New Bootstrap.kt implementation
object Bootstrap {
    
    fun bootstrap(config: MuseApplicationConfig, args: Array<String> = emptyArray()): MuseApplication {
        // Phase 1: Build PropertyResolver from configurations
        val propertyResolver = buildPropertyResolver(config.configs.values, args)
        
        // Phase 2: Resolve all properties (with placeholder resolution)
        val resolvedProperties = propertyResolver.toProperties()
        
        // Phase 3: Create expression evaluator with resolved properties
        val expressionEvaluator = MuseExpressionEvaluatorImpl(
            dataWeaveExecutor = MuseDataWeaveExecutor(),
            properties = resolvedProperties
        )
        
        // Phase 4: Create context
        val context = MuseContext(
            flows = config.flows,
            configs = config.configs,
            properties = resolvedProperties,
            expressionEvaluator = expressionEvaluator,
            propertyResolver = propertyResolver  // NEW: Store resolver for runtime access
        )
        
        // Phase 5: Apply remaining configurations (non-property configs)
        config.configs.values
            .filter { it !is PropertyConfiguration }
            .forEach { cfg -> config.factory.applyConfig(cfg, context) }
        
        return MuseApplication(context)
    }
    
    private fun buildPropertyResolver(
        configs: Collection<MuseConfiguration>,
        args: Array<String>
    ): PropertyResolver {
        val builder = PropertySourceBuilder()
        
        // 1. Command line arguments (highest priority)
        if (args.isNotEmpty()) {
            builder.withCommandLineArgs(parseCommandLineArgs(args))
        }
        
        // 2. JVM system properties
        builder.withJvmProperties()
        
        // 3. Environment variables
        builder.withEnvironment()
        
        // 4. Process property configurations in order
        val propertyConfigs = configs.filterIsInstance<PropertyConfiguration>()
        
        // 4a. Determine active profiles
        val activeProfiles = determineActiveProfiles(propertyConfigs)
        
        // 4b. Add file-based sources (base + profile-specific)
        propertyConfigs.filterIsInstance<ConfigurationProperties>()
            .sortedBy { it.priority ?: PropertyPriority.APPLICATION_FILE }
            .forEach { cfg ->
                val path = Path.of(cfg.file)
                when {
                    cfg.profile != null && activeProfiles.contains(cfg.profile) -> {
                        addFileSource(builder, path, cfg, profile = cfg.profile)
                    }
                    cfg.profile == null -> {
                        addFileSource(builder, path, cfg)
                    }
                }
            }
        
        // 4c. Add global properties (lowest priority)
        propertyConfigs.filterIsInstance<GlobalProperty>()
            .sortedBy { it.priority ?: PropertyPriority.DEFAULT }
            .forEach { cfg ->
                builder.withGlobalProperty(cfg.name, cfg.value, cfg.priority)
            }
        
        return builder.build()
    }
    
    private fun addFileSource(
        builder: PropertySourceBuilder,
        path: Path,
        config: ConfigurationProperties,
        profile: String? = null
    ) {
        when (path.extension.lowercase()) {
            "properties" -> builder.withPropertiesFile(path, config.encoding)
            "yaml", "yml" -> builder.withYamlFile(path, config.encoding)
        }
    }
    
    private fun determineActiveProfiles(configs: Collection<PropertyConfiguration>): List<String> {
        // Check for ProfileConfiguration
        val profileConfig = configs.filterIsInstance<ProfileConfiguration>().firstOrNull()
        if (profileConfig != null) {
            return profileConfig.activeProfiles
        }
        
        // Check environment: MUSE_PROFILES_ACTIVE or muse.profiles.active
        val envProfiles = System.getenv("MUSE_PROFILES_ACTIVE")
        if (!envProfiles.isNullOrEmpty()) {
            return envProfiles.split(",")
        }
        
        val sysProfiles = System.getProperty("muse.profiles.active")
        if (!sysProfiles.isNullOrEmpty()) {
            return sysProfiles.split(",")
        }
        
        return emptyList()
    }
    
    private fun parseCommandLineArgs(args: Array<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var i = 0
        while (i < args.size) {
            when {
                args[i].startsWith("--") && args[i].contains("=") -> {
                    val (key, value) = args[i].removePrefix("--").split("=", limit = 2)
                    result[key] = value
                }
                args[i].startsWith("--") && i + 1 < args.size && !args[i + 1].startsWith("-") -> {
                    result[args[i].removePrefix("--")] = args[i + 1]
                    i++
                }
                args[i].startsWith("-D") && args[i].contains("=") -> {
                    val (key, value) = args[i].removePrefix("-D").split("=", limit = 2)
                    result[key] = value
                }
            }
            i++
        }
        return result
    }
}
```

### 6.2 Enhanced PropertiesLoader

```kotlin
/**
 * Enhanced PropertiesLoader that creates PropertySources instead of
 * directly populating Properties.
 */
object PropertiesLoader {
    
    /**
     * Converts configurations to PropertySources.
     */
    fun toPropertySources(configs: Collection<MuseConfiguration>): List<PropertySource> {
        return configs
            .filterIsInstance<PropertyConfiguration>()
            .mapNotNull { toPropertySource(it) }
    }
    
    private fun toPropertySource(config: PropertyConfiguration): PropertySource? {
        return when (config) {
            is GlobalProperty -> MapPropertySource(
                "global-${config.name}",
                config.priority ?: PropertyPriority.DEFAULT,
                PropertySourceType.DEFAULT,
                mapOf(config.name to config.value)
            )
            
            is ConfigurationProperties -> {
                val path = Path.of(config.file)
                when (path.extension.lowercase()) {
                    "properties" -> PropertiesFileSource(path, config.encoding)
                    "yaml", "yml" -> YamlFileSource(path, config.encoding)
                    else -> null
                }
            }
            
            is EnvironmentProperties -> EnvironmentPropertySource(
                "environment",
                config.prefix,
                config.removePrefix
            )
            
            is JvmSystemProperties -> JvmSystemPropertySource(
                "jvm-system",
                config.prefix,
                config.removePrefix
            )
            
            is EncryptedProperty -> EncryptedPropertySource(config)
            
            else -> null
        }
    }
    
    /**
     * Legacy method for backward compatibility.
     * Directly populates a Properties object.
     */
    @Deprecated(
        "Use PropertyResolver instead",
        ReplaceWith("PropertyResolver(sources).toProperties()")
    )
    fun apply(config: MuseConfiguration, properties: java.util.Properties) {
        when (config) {
            is GlobalProperty -> {
                val resolved = resolvePlaceholders(config.value, properties)
                properties.setProperty(config.name, resolved)
            }
            is ConfigurationProperties -> {
                loadPropertiesFile(config.file, config.encoding, properties)
            }
            is EnvironmentProperties -> {
                applyEnvironmentProperties(config, properties)
            }
            is JvmSystemProperties -> {
                applyJvmProperties(config, properties)
            }
        }
    }
}
```

---

## 7. Profile Support

### 7.1 Profile-Aware Property Loading

```kotlin
/**
 * Loads profile-specific property files.
 * 
 * File naming convention:
 * - application.properties (base)
 * - application-dev.properties (dev profile)
 * - application-prod.properties (prod profile)
 */
class ProfileAwarePropertyLoader(
    private val baseDirectory: Path,
    private val activeProfiles: List<String>
) {
    
    fun loadAll(): List<PropertySource> {
        val sources = mutableListOf<PropertySource>()
        
        // Load base files first
        findBaseFiles().forEach { file ->
            sources.add(createSource(file, null))
        }
        
        // Load profile-specific files (lower priority)
        activeProfiles.forEach { profile ->
            findProfileFiles(profile).forEach { file ->
                sources.add(createSource(file, profile))
            }
        }
        
        return sources
    }
    
    private fun findBaseFiles(): List<Path> {
        return listOf("application.properties", "application.yaml", "application.yml")
            .map { baseDirectory.resolve(it) }
            .filter { it.exists() }
    }
    
    private fun findProfileFiles(profile: String): List<Path> {
        return listOf(
            "application-$profile.properties",
            "application-$profile.yaml",
            "application-$profile.yml"
        ).map { baseDirectory.resolve(it) }
            .filter { it.exists() }
    }
    
    private fun createSource(file: Path, profile: String?): PropertySource {
        val baseSource = when (file.extension.lowercase()) {
            "properties" -> PropertiesFileSource(file)
            "yaml", "yml" -> YamlFileSource(file)
            else -> throw IllegalArgumentException("Unsupported file type: $file")
        }
        
        return if (profile != null) {
            ProfilePropertySourceWrapper(baseSource, profile)
        } else {
            baseSource
        }
    }
}
```

### 7.2 Profile-Specific Environment Variables

```kotlin
/**
 * Environment variables can be profile-specific:
 * MYAPP_DEV_DB_HOST → db.host (when 'dev' profile is active)
 * MYAPP_PROD_DB_HOST → db.host (when 'prod' profile is active)
 */
class ProfileAwareEnvironmentPropertySource(
    private val appName: String,
    private val activeProfiles: List<String>,
    private val delegate: EnvironmentPropertySource = EnvironmentPropertySource()
) : PropertySource {
    
    override val name = "profile-environment"
    override val priority = PropertyPriority.ENVIRONMENT
    override val sourceType = PropertySourceType.ENVIRONMENT
    
    override fun getProperty(key: String): String? {
        // Try profile-specific env vars first
        for (profile in activeProfiles.reversed()) {
            val profileKey = "${appName.uppercase()}_${profile.uppercase()}_${toEnvVarFormat(key)}"
            val value = delegate.getProperty(profileKey)
            if (value != null) return value
        }
        
        // Fall back to non-profile env var
        val appKey = "${appName.uppercase()}_${toEnvVarFormat(key)}"
        return delegate.getProperty(appKey) ?: delegate.getProperty(key)
    }
    
    override fun getPropertyNames(): Set<String> {
        return delegate.getPropertyNames()
    }
    
    private fun toEnvVarFormat(key: String): String {
        return key.uppercase().replace(".", "_")
    }
}
```

---

## 8. Encrypted Properties

### 8.1 Design

```kotlin
/**
 * Property source that decrypts values on access.
 */
class EncryptedPropertySource(
    private val config: EncryptedProperty,
    private val decryptor: PropertyDecryptor = DefaultPropertyDecryptor()
) : PropertySource {
    
    override val name = "encrypted-${config.name}"
    override val priority = config.priority ?: PropertyPriority.DEFAULT
    override val sourceType = PropertySourceType.DEFAULT
    
    override fun getProperty(key: String): String? {
        if (key != config.name) return null
        return decryptor.decrypt(config.encryptedValue, config.encryptionAlgorithm)
    }
    
    override fun getPropertyNames(): Set<String> = setOf(config.name)
}

interface PropertyDecryptor {
    fun decrypt(encryptedValue: String, algorithm: String): String
}

class DefaultPropertyDecryptor(
    private val secretKeyProvider: () -> String = { 
        System.getenv("MUSE_ENCRYPTION_KEY") 
            ?: System.getProperty("muse.encryption.key")
            ?: throw IllegalStateException("No encryption key configured")
    }
) : PropertyDecryptor {
    
    override fun decrypt(encryptedValue: String, algorithm: String): String {
        val secretKey = secretKeyProvider()
        return when (algorithm.uppercase()) {
            "AES" -> decryptAES(encryptedValue, secretKey)
            "BASE64" -> String(Base64.getDecoder().decode(encryptedValue))
            else -> throw IllegalArgumentException("Unsupported encryption algorithm: $algorithm")
        }
    }
    
    private fun decryptAES(encryptedValue: String, secretKey: String): String {
        // Implementation using javax.crypto
        val key = SecretKeySpec(secretKey.toByteArray(), "AES")
        val cipher = Cipher.getInstance("AES")
        cipher.init(Cipher.DECRYPT_MODE, key)
        val decoded = Base64.getDecoder().decode(encryptedValue)
        return String(cipher.doFinal(decoded))
    }
}
```

### 8.2 Encryption Utility

```kotlin
/**
 * Utility for encrypting property values during development/deployment.
 */
object PropertyEncryptionUtil {
    
    fun encrypt(value: String, secretKey: String, algorithm: String = "AES"): String {
        return when (algorithm.uppercase()) {
            "AES" -> encryptAES(value, secretKey)
            "BASE64" -> Base64.getEncoder().encodeToString(value.toByteArray())
            else -> throw IllegalArgumentException("Unsupported algorithm: $algorithm")
        }
    }
    
    private fun encryptAES(value: String, secretKey: String): String {
        val key = SecretKeySpec(secretKey.toByteArray(), "AES")
        val cipher = Cipher.getInstance("AES")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return Base64.getEncoder().encodeToString(cipher.doFinal(value.toByteArray()))
    }
    
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) {
            println("Usage: PropertyEncryptionUtil <value> <secretKey> [algorithm]")
            return
        }
        val encrypted = encrypt(args[0], args[1], args.getOrNull(2) ?: "AES")
        println("Encrypted: $encrypted")
    }
}
```

---

## 9. Hot Reload Support (Optional)

### 9.1 Design

```kotlin
/**
 * Property source that supports hot reload.
 */
interface ReloadablePropertySource : PropertySource {
    fun reload(): Boolean
    fun lastModified(): Long
}

/**
 * File-based property source with hot reload capability.
 */
class ReloadableFilePropertySource(
    private val delegate: FilePropertySource
) : ReloadablePropertySource, PropertySource by delegate {
    
    private var lastModifiedTime: Long = Files.getLastModifiedTime(delegate.file).toMillis()
    private var cachedProperties: Map<String, String> = delegate.loadProperties()
    private val listeners = mutableListOf<PropertyChangeListener>()
    
    override fun getProperty(key: String): String? = cachedProperties[key]
    
    override fun getPropertyNames(): Set<String> = cachedProperties.keys
    
    override fun reload(): Boolean {
        val currentModified = Files.getLastModifiedTime(delegate.file).toMillis()
        if (currentModified > lastModifiedTime) {
            val oldProperties = cachedProperties
            cachedProperties = delegate.loadProperties()
            lastModifiedTime = currentModified
            
            // Notify listeners of changes
            notifyChanges(oldProperties, cachedProperties)
            return true
        }
        return false
    }
    
    override fun lastModified(): Long = lastModifiedTime
    
    override fun addChangeListener(listener: PropertyChangeListener): Boolean {
        return listeners.add(listener)
    }
    
    override fun removeChangeListener(listener: PropertyChangeListener): Boolean {
        return listeners.remove(listener)
    }
    
    private fun notifyChanges(oldProps: Map<String, String>, newProps: Map<String, String>) {
        val allKeys = oldProps.keys + newProps.keys
        for (key in allKeys) {
            val oldValue = oldProps[key]
            val newValue = newProps[key]
            if (oldValue != newValue) {
                listeners.forEach { it.onPropertyChange(key, oldValue, newValue) }
            }
        }
    }
}

/**
 * Background service that periodically checks for property changes.
 */
class PropertyReloadService(
    private val resolver: PropertyResolver,
    private val intervalMs: Long = 5000
) {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var running = false
    
    fun start() {
        if (running) return
        running = true
        executor.scheduleAtFixedRate(::checkForChanges, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
    }
    
    fun stop() {
        running = false
        executor.shutdown()
    }
    
    private fun checkForChanges() {
        resolver.sources
            .filterIsInstance<ReloadablePropertySource>()
            .forEach { source ->
                if (source.reload()) {
                    log.info("Reloaded properties from ${source.name}")
                }
            }
    }
}
```

---

## 10. Performance Considerations

### 10.1 Optimization Strategies

| Operation | Naive Complexity | Optimized Complexity | Strategy |
|-----------|------------------|---------------------|----------|
| Single property lookup | O(S × K) | O(S) | Pre-normalize env vars |
| Placeholder resolution | O(N × P) | O(P) | Cycle detection with path tracking |
| Build resolver | O(S × K) | O(S × K) | Lazy loading of file sources |
| Hot reload check | O(S × K) | O(S) | Last-modified timestamp check |

Where:
- S = number of sources
- K = average keys per source
- N = placeholder nesting depth
- P = number of placeholders

### 10.2 Caching Strategy

```kotlin
/**
 * Cached property resolver for high-performance scenarios.
 */
class CachedPropertyResolver(
    private val delegate: PropertyResolver,
    private val cache: ConcurrentMap<String, Optional<String>> = ConcurrentHashMap()
) : PropertyResolver by delegate {
    
    override fun resolve(key: String): String? {
        return cache.getOrPut(key) {
            Optional.ofNullable(delegate.resolve(key))
        }.orElse(null)
    }
    
    fun invalidateCache() {
        cache.clear()
    }
    
    fun invalidateCache(key: String) {
        cache.remove(key)
    }
}
```

---

## 11. Migration Guide

### 11.1 From Current Implementation

**Before:**
```kotlin
val properties = Properties()
PropertiesLoader.apply(config, properties)
```

**After:**
```kotlin
val resolver = PropertySourceBuilder()
    .withJvmProperties()
    .withEnvironment()
    .withPropertiesFile(Path.of("config/application.properties"))
    .build()
val properties = resolver.toProperties()
```

### 11.2 Backward Compatibility

The enhanced `PropertiesLoader.apply()` method remains available but deprecated:

```kotlin
@Deprecated("Use PropertyResolver instead")
fun apply(config: MuseConfiguration, properties: Properties) {
    // Legacy implementation preserved
}
```

---

## 12. Testing Strategy

### 12.1 Unit Tests

```kotlin
class PropertyResolverTest {
    
    @Test
    fun `priority ordering - JVM overrides environment`() {
        val resolver = PropertySourceBuilder()
            .withEnvironment()
            .withJvmProperties()
            .build()
        
        // Given: env var DB_HOST=env-value, -Ddb.host=jvm-value
        withEnvironment("DB_HOST" to "env-value") {
            withSystemProperty("db.host" to "jvm-value") {
                assertEquals("jvm-value", resolver.resolve("db.host"))
            }
        }
    }
    
    @Test
    fun `env var normalization - DB_HOST matches db-host`() {
        val normalizer = PropertyNameNormalizer
        assertEquals("db.host", normalizer.normalizeToDotNotation("DB_HOST"))
        assertEquals("db.host", normalizer.normalizeToDotNotation("db-host"))
        assertEquals("db.host", normalizer.normalizeToDotNotation("dbHost"))
    }
    
    @Test
    fun `placeholder resolution with default value`() {
        val resolver = PropertySourceBuilder()
            .withGlobalProperty("greeting", "Hello, \${name:World}!")
            .build()
        
        assertEquals("Hello, World!", resolver.resolve("greeting"))
    }
    
    @Test
    fun `circular reference detection`() {
        val resolver = PropertySourceBuilder()
            .withGlobalProperty("a", "\${b}")
            .withGlobalProperty("b", "\${a}")
            .build()
        
        assertThrows<CircularPropertyReferenceException> {
            resolver.resolve("a")
        }
    }
}
```

---

## 13. Summary

### Key Design Decisions

1. **PropertySource abstraction** - Enables clean separation of concerns and priority management
2. **Normalization function over BiMap** - Stateless, O(1) lookup, memory efficient
3. **Lazy loading** - File sources loaded on first access
4. **Placeholder resolution at resolve-time** - Not at load-time, enabling dynamic references
5. **Profile support via naming convention** - `application-{profile}.properties`
6. **Optional hot reload** - Via `ReloadablePropertySource` interface
7. **Encrypted properties** - Via `EncryptedPropertySource` with pluggable decryptor

### Implementation Phases

| Phase | Scope | Effort |
|-------|-------|--------|
| 1 | Core PropertySource hierarchy + PropertyResolver | 2-3 days |
| 2 | Naming convention normalization | 1 day |
| 3 | Enhanced placeholder resolution | 1 day |
| 4 | Profile support | 1-2 days |
| 5 | Encrypted properties | 1 day |
| 6 | Hot reload (optional) | 1-2 days |
| 7 | Migration + testing | 2 days |

**Total: 9-12 days**