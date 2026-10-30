package dev.museframework.file.api.dsl

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.model.config.ConfigRef
import dev.museframework.dsl.builder.FlowBuilder
import dev.museframework.file.api.config.FileConnectionConfig
import dev.museframework.file.api.config.FileListenerConfig
import dev.museframework.file.api.model.LocalFileMatcher
import dev.museframework.file.api.model.WatermarkMode
import java.util.concurrent.TimeUnit

@MuseDsl
class FileListenerBuilder internal constructor(connectionConfig: ConfigRef) : FileComponentBuilder(connectionConfig) {
    var directory: String = "."
    var recursive: Boolean = true
    var matcher: LocalFileMatcher? = null
    var watermarkMode: WatermarkMode = WatermarkMode.DISABLED
    var pollingFrequencyMillis: Long = 1_000
    var startDelayMillis: Long = 0
    var timeBetweenSizeCheck: Long? = null
    var timeBetweenSizeCheckUnit: TimeUnit? = null
    var outputMimeType: String? = null
    var encoding: String? = null
    var autoDelete: Boolean = false
    var moveToDirectory: String? = null
    var renameTo: String? = null
    var applyPostActionWhenFailed: Boolean = true
    var overwrite: Boolean = false

    fun build(): FileListenerConfig {
        require(directory.isNotBlank()) { "File listener directory must not be blank" }
        require(pollingFrequencyMillis > 0) { "pollingFrequencyMillis must be greater than zero" }
        require(startDelayMillis >= 0) { "startDelayMillis must be greater than or equal to zero" }
        require(timeBetweenSizeCheck == null || timeBetweenSizeCheck!! >= 0) {
            "timeBetweenSizeCheck must be greater than or equal to zero"
        }
        require(!autoDelete || moveToDirectory == null) {
            "autoDelete and moveToDirectory are mutually exclusive"
        }
        require(renameTo == null || moveToDirectory != null) {
            "renameTo requires moveToDirectory"
        }
        val location = sourceLocation()
        return FileListenerConfig(
            docName = docName,
            docId = docId,
            docDescription = docDescription,
            sourceFile = location.sourceFile,
            sourceLine = location.sourceLine,
            sourceClass = location.sourceClass,
            connectionConfig = connectionConfig,
            directory = directory,
            recursive = recursive,
            matcher = matcher,
            watermarkMode = watermarkMode,
            pollingFrequencyMillis = pollingFrequencyMillis,
            startDelayMillis = startDelayMillis,
            timeBetweenSizeCheck = timeBetweenSizeCheck,
            timeBetweenSizeCheckUnit = timeBetweenSizeCheckUnit,
            outputMimeType = outputMimeType,
            encoding = encoding,
            autoDelete = autoDelete,
            moveToDirectory = moveToDirectory,
            renameTo = renameTo,
            applyPostActionWhenFailed = applyPostActionWhenFailed,
            overwrite = overwrite,
        )
    }
}

fun FlowBuilder.fileListener(connectionConfig: ConfigRef, block: FileListenerBuilder.() -> Unit = {}) {
    source(FileListenerBuilder(connectionConfig).apply(block).build())
}

fun FlowBuilder.fileListener(connectionConfig: FileConnectionConfig, block: FileListenerBuilder.() -> Unit = {}) =
    fileListener(ConfigRef.ByReference(connectionConfig), block)

fun FlowBuilder.fileListener(configName: String, block: FileListenerBuilder.() -> Unit = {}) =
    fileListener(ConfigRef.ByName(configName), block)
