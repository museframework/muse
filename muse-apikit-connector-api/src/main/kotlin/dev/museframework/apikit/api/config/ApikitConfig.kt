package dev.museframework.apikit.api.config

import dev.museframework.dsl.api.model.config.MuseConfiguration
import java.util.UUID

data class ApikitConfig(
    val name: String,
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    /** Required classpath path of the RAML specification used to build APIKit routes. */
    val spec: String? = null,
    /** Disables request header and query-parameter validation; specification bootstrap remains mandatory. */
    val disableValidations: Boolean = false,
) : MuseConfiguration
