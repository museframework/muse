package dev.museframework.file.api.dsl

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.model.config.ConfigRef
import dev.museframework.dsl.api.util.CallSiteCapture
import dev.museframework.file.api.config.FileConnectionConfig
import java.util.UUID
import java.util.concurrent.TimeUnit

@MuseDsl
class FileConnectionBuilder {
    var name: String = ""
    var docName: String = ""
    var docId: String = UUID.randomUUID().toString()
    var docDescription: String? = null
    var sourceFile: String? = null
    var sourceLine: Int? = null
    var sourceClass: String? = null
    var workingDirectory: String? = null
    var timeBetweenSizeCheck: Long? = null
    var timeBetweenSizeCheckUnit: TimeUnit = TimeUnit.MILLISECONDS

    fun build(): FileConnectionConfig {
        val callSite = CallSiteCapture.capture()
        val resolvedName = name.ifBlank { docName }
        return FileConnectionConfig(
            name = resolvedName,
            docName = docName.ifBlank { resolvedName },
            docId = docId,
            docDescription = docDescription,
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
            workingDirectory = workingDirectory,
            timeBetweenSizeCheck = timeBetweenSizeCheck,
            timeBetweenSizeCheckUnit = timeBetweenSizeCheckUnit,
        )
    }
}

fun fileConnection(block: FileConnectionBuilder.() -> Unit = {}): FileConnectionConfig =
    FileConnectionBuilder().apply(block).build()

fun fileConnection(name: String): ConfigRef = ConfigRef.ByName(name)

fun fileConnection(config: FileConnectionConfig): ConfigRef = ConfigRef.ByReference(config)
