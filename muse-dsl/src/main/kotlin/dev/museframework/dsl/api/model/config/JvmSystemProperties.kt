package dev.museframework.dsl.api.model.config

import java.util.*

/**
 * Configuration that loads JVM system properties from the runtime.
 * This allows accessing real JVM parameters like java.version, user.home, os.name, etc.
 * via System.getProperties().
 *
 * Example usage:
 * - Load all JVM properties: JvmSystemProperties(docName = "JVM Props")
 * - Load with prefix: JvmSystemProperties(docName = "App Props", prefix = "app.")
 *
 * Common JVM properties available:
 * - java.version, java.vendor, java.home
 * - user.name, user.home, user.dir
 * - os.name, os.arch, os.version
 * - file.encoding, file.separator
 * - java.class.path, java.library.path
 */
data class JvmSystemProperties(
    override val docName: String,
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    /**
     * Optional prefix to filter JVM properties.
     * Only properties starting with this prefix will be loaded.
     * Empty string (default) means load all JVM properties.
     */
    val prefix: String = "",
    /**
     * If true, the prefix will be removed from the property name.
     * Example: prefix="app.", property="app.database.url" → property "database.url"
     * If false: prefix="app.", property="app.database.url" → property "app.database.url"
     */
    val removePrefix: Boolean = false,
) : MuseConfiguration
