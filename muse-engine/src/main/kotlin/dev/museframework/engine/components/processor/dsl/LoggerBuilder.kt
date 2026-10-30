package dev.museframework.engine.components.processor.dsl

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.config.LogLevel
import dev.museframework.dsl.api.config.LoggerConfig
import dev.museframework.dsl.api.util.CallSiteCapture
import java.util.*

@MuseDsl
class LoggerBuilder {
    var docId: String = UUID.randomUUID().toString()
    var docName: String = ""
    var docDescription: String? = null
    var sourceFile: String? = null
    var sourceLine: Int? = null
    var sourceClass: String? = null
    var level: LogLevel = LogLevel.INFO
    var message: String = "#[payload]"

    fun build(): LoggerConfig {
        val callSite = CallSiteCapture.capture()
        return LoggerConfig(
            docId = docId,
            docName = docName,
            docDescription = docDescription,
            message = message,
            level = level,
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
        )
    }
}
