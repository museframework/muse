package dev.museframework.dsl.api.config

import dev.museframework.dsl.api.model.component.Processor

data class LoggerConfig(
    override val docName: String,
    override val docId: String,
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val level: LogLevel = LogLevel.INFO,
    val message: String = "#[payload]",
) : Processor

enum class LogLevel { TRACE, DEBUG, INFO, WARN, ERROR }
