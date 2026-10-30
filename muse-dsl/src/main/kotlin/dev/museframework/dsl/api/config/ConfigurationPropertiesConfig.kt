package dev.museframework.dsl.api.config

import dev.museframework.dsl.api.model.config.MuseConfiguration

/**
 * Configuration for loading properties from an external file.
 * Maps to MuleSoft's `configuration-properties` element.
 */
data class ConfigurationPropertiesConfig(
    override val docName: String,
    override val docId: String,
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    /** Path to the properties file (e.g., "properties/config.yaml") */
    val file: String,
) : MuseConfiguration
