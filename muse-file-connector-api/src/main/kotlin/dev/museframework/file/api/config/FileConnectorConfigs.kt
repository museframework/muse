package dev.museframework.file.api.config

import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.model.component.Processor
import dev.museframework.dsl.api.model.component.Source
import dev.museframework.dsl.api.model.config.ConfigRef
import dev.museframework.dsl.api.model.config.MuseConfiguration
import dev.museframework.file.api.model.FileWriteMode
import dev.museframework.file.api.model.LocalFileMatcher
import dev.museframework.file.api.model.LocalSubsetList
import dev.museframework.file.api.model.WatermarkMode
import java.util.UUID
import java.util.concurrent.TimeUnit

data class FileConnectionConfig(
    val name: String = "",
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val workingDirectory: String? = null,
    val timeBetweenSizeCheck: Long? = null,
    val timeBetweenSizeCheckUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : MuseConfiguration {
    init {
        require(timeBetweenSizeCheck == null || timeBetweenSizeCheck >= 0) {
            "timeBetweenSizeCheck must be greater than or equal to zero"
        }
    }
}

data class FileReadConfig(
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val connectionConfig: ConfigRef,
    val path: String,
    val lock: Boolean = false,
    val timeBetweenSizeCheck: Long? = null,
    val timeBetweenSizeCheckUnit: TimeUnit? = null,
    val outputMimeType: String? = null,
    val encoding: String? = null,
    val targetConfig: TargetConfig? = null,
) : Processor

data class FileWriteConfig(
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val connectionConfig: ConfigRef,
    val path: String,
    val content: String? = null,
    val createParentDirectories: Boolean = true,
    val lock: Boolean = false,
    val mode: FileWriteMode = FileWriteMode.OVERWRITE,
) : Processor

data class FileListConfig(
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val connectionConfig: ConfigRef,
    val directoryPath: String,
    val recursive: Boolean = false,
    val matcher: LocalFileMatcher? = null,
    val timeBetweenSizeCheck: Long? = null,
    val timeBetweenSizeCheckUnit: TimeUnit? = null,
    val subset: LocalSubsetList? = null,
    val targetConfig: TargetConfig? = null,
) : Processor

data class FileCopyConfig(
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val connectionConfig: ConfigRef,
    val sourcePath: String,
    val targetPath: String,
    val createParentDirectories: Boolean = true,
    val overwrite: Boolean = false,
    val renameTo: String? = null,
) : Processor

data class FileMoveConfig(
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val connectionConfig: ConfigRef,
    val sourcePath: String,
    val targetPath: String,
    val createParentDirectories: Boolean = true,
    val overwrite: Boolean = false,
    val renameTo: String? = null,
) : Processor

data class FileDeleteConfig(
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val connectionConfig: ConfigRef,
    val path: String,
) : Processor

data class FileRenameConfig(
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val connectionConfig: ConfigRef,
    val path: String,
    val to: String,
    val overwrite: Boolean = false,
) : Processor

data class FileCreateDirectoryConfig(
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val connectionConfig: ConfigRef,
    val directoryPath: String,
) : Processor

/**
 * Reusable matcher translated from a top-level Mule `<file:matcher>`.
 * List and listener components carry the resolved [LocalFileMatcher]; the runtime does not open a resource for this config.
 */
data class FileMatcherConfig(
    val name: String = "",
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val matcher: LocalFileMatcher = LocalFileMatcher(),
) : MuseConfiguration

data class FileListenerConfig(
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val connectionConfig: ConfigRef,
    val directory: String = ".",
    val recursive: Boolean = true,
    val matcher: LocalFileMatcher? = null,
    val watermarkMode: WatermarkMode = WatermarkMode.DISABLED,
    val pollingFrequencyMillis: Long = 1_000,
    val startDelayMillis: Long = 0,
    val timeBetweenSizeCheck: Long? = null,
    val timeBetweenSizeCheckUnit: TimeUnit? = null,
    val outputMimeType: String? = null,
    val encoding: String? = null,
    val autoDelete: Boolean = false,
    val moveToDirectory: String? = null,
    val renameTo: String? = null,
    val applyPostActionWhenFailed: Boolean = true,
    val overwrite: Boolean = false,
) : Source
