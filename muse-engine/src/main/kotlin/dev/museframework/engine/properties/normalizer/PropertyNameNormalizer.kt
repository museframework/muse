package dev.museframework.engine.properties.normalizer

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
    
    companion object : PropertyNameNormalizer by DefaultPropertyNameNormalizer
}

object DefaultPropertyNameNormalizer : PropertyNameNormalizer {
    
    private val CAMEL_CASE_REGEX = Regex("([a-z])([A-Z])")
    
    override fun normalizeToDotNotation(name: String): String {
        return name
            .replace(CAMEL_CASE_REGEX) { 
                "${it.groupValues[1]}.${it.groupValues[2].lowercase()}" 
            }
            .replace("-", ".")
            .replace("_", ".")
            .lowercase()
            .replace(Regex("\\.+"), ".")
            .trim('.')
    }
    
    override fun generateVariants(normalizedName: String): Set<String> {
        if (normalizedName.isEmpty()) return emptySet()
        
        val parts = normalizedName.split(".")
        val variants = mutableSetOf<String>()
        
        variants.add(normalizedName)
        variants.add(parts.joinToString("-"))
        variants.add(parts.joinToString("_") { it.uppercase() })
        variants.add(parts.mapIndexed { i, part ->
            if (i == 0) part else part.replaceFirstChar { it.uppercase() }
        }.joinToString(""))
        
        return variants
    }
}

/**
 * Strategy for matching environment variables to property names.
 */
class EnvironmentVariableMatcher(
    private val normalizer: PropertyNameNormalizer = PropertyNameNormalizer
) {
    /**
     * Builds a lookup map from environment variables.
     */
    fun buildLookupMap(envVars: Map<String, String> = System.getenv()): Map<String, String> {
        return envVars.entries.associate { (envKey, value) ->
            normalizer.normalizeToDotNotation(envKey) to value
        }
    }
    
    /**
     * Finds all environment variables that could match a property name.
     */
    fun findMatchingEnvVars(
        propertyName: String, 
        envVars: Map<String, String> = System.getenv()
    ): List<Pair<String, String>> {
        val normalized = normalizer.normalizeToDotNotation(propertyName)
        val variants = normalizer.generateVariants(normalized)
        
        return envVars.entries
            .filter { (key, _) ->
                normalizer.normalizeToDotNotation(key) == normalized || variants.contains(key)
            }
            .map { it.key to it.value }
    }
}