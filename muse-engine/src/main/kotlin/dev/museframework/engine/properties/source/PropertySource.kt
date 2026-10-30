package dev.museframework.engine.properties.source

/**
 * Represents a source of properties with associated priority.
 * Higher priority values take precedence.
 */
interface PropertySource {
    /**
     * Human-readable name for this source (used in debugging/logging)
     */
    val name: String
    
    /**
     * Priority value - higher values take precedence.
     * See [PropertyPriority] for standard values.
     */
    val priority: Int
    
    /**
     * Type of this property source
     */
    val sourceType: PropertySourceType
    
    /**
     * Returns the property value for the given key, or null if not found.
     * The key should be in normalized dot-notation format.
     */
    fun getProperty(key: String): String?
    
    /**
     * Returns all property names this source contains.
     * Used for enumeration and debugging.
     */
    fun getPropertyNames(): Set<String>
    
    /**
     * Optional: Support for property change notifications.
     * Override to return true if this source supports hot reload.
     */
    fun addChangeListener(listener: PropertyChangeListener): Boolean = false
    
    /**
     * Optional: Remove a previously registered change listener.
     */
    fun removeChangeListener(listener: PropertyChangeListener): Boolean = false
}

/**
 * Enumeration of property source types with their default priorities.
 */
enum class PropertySourceType(
    val defaultPriority: Int,
    val description: String
) {
    /**
     * Command-line arguments (--key=value or -Dkey=value)
     * Highest priority - user explicitly set at runtime
     */
    COMMAND_LINE(1000, "Command-line arguments"),
    
    /**
     * JVM system properties (-D flags)
     * High priority - set at JVM startup
     */
    JVM_SYSTEM(800, "JVM system properties"),
    
    /**
     * Operating system environment variables
     * Medium-high priority - set by deployment environment
     */
    ENVIRONMENT(600, "Environment variables"),
    
    /**
     * Application configuration files (.properties, .yaml)
     * Medium priority - bundled with application
     */
    APPLICATION_FILE(400, "Application configuration files"),
    
    /**
     * Profile-specific configuration files
     * Slightly lower than base application files
     */
    PROFILE_SPECIFIC(350, "Profile-specific configuration"),
    
    /**
     * Default values and GlobalProperty configurations
     * Lowest priority - fallback values
     */
    DEFAULT(200, "Default values"),
    
    /**
     * Absolute fallback values
     * Used for hardcoded defaults
     */
    FALLBACK(100, "Fallback defaults");
}

/**
 * Standard priority constants for property sources.
 */
object PropertyPriority {
    const val COMMAND_LINE = 1000
    const val JVM_SYSTEM = 800
    const val ENVIRONMENT = 600
    const val APPLICATION_FILE = 400
    const val PROFILE_SPECIFIC = 350
    const val DEFAULT = 200
    const val FALLBACK = 100
    
    /**
     * Returns the appropriate priority for a profile-specific source.
     * Profile sources have slightly lower priority than base sources.
     */
    fun forProfile(basePriority: Int): Int = basePriority - 50
}

/**
 * Listener interface for property change notifications.
 * Used for hot reload support.
 */
interface PropertyChangeListener {
    /**
     * Called when a property value changes.
     * 
     * @param key The property name that changed
     * @param oldValue The previous value (null if property was added)
     * @param newValue The new value (null if property was removed)
     */
    fun onPropertyChange(key: String, oldValue: String?, newValue: String?)
}

/**
 * Exception thrown when a property is not found.
 */
class PropertyNotFoundException(
    val key: String,
    val searchedSources: List<String> = emptyList()
) : RuntimeException(
    buildString {
        append("Property '$key' not found")
        if (searchedSources.isNotEmpty()) {
            append(" in sources: ${searchedSources.joinToString(", ")}")
        }
    }
)

/**
 * Exception thrown when a circular property reference is detected.
 */
class CircularPropertyReferenceException(
    val property: String,
    val referenceChain: List<String>
) : RuntimeException(
    "Circular property reference detected: ${referenceChain.joinToString(" → ")}"
)