package dev.museframework.dsl.api.model.config

import java.util.*

/**
 * Configuration that loads environment variables from the system.
 * This allows accessing real OS environment variables like PATH, HOME, USER, etc.
 * via the property system with optional prefix.
 *
 * Example usage in Kubernetes:
 * - Load all env vars: EnvironmentProperties(docName = "System Env")
 * - Load with prefix: EnvironmentProperties(docName = "K8s Env", prefix = "KUBERNETES_")
 *
 * The prefix allows filtering specific environment variables, for example:
 * - prefix = "" (default) - loads all env vars
 * - prefix = "APP_" - loads only env vars starting with APP_
 * - prefix = "K8S_" - loads only Kubernetes-specific env vars
 */
data class EnvironmentProperties(
    override val docName: String,
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    /**
     * Optional prefix to filter environment variables.
     * Only env vars starting with this prefix will be loaded.
     * Empty string (default) means load all env vars.
     */
    val prefix: String = "",
    /**
     * If true, the prefix will be removed from the property name.
     * Example: prefix="APP_", variable="APP_DATABASE_URL" → property "DATABASE_URL"
     * If false: prefix="APP_", variable="APP_DATABASE_URL" → property "APP_DATABASE_URL"
     */
    val removePrefix: Boolean = false,
) : MuseConfiguration
