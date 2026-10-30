package dev.museframework.dsl.api.model.config

import java.util.*

data class GlobalProperty(
    override val docName: String,
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val name: String,
    val value: String,
) : MuseConfiguration
