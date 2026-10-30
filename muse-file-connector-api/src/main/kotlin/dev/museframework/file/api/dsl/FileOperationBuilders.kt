package dev.museframework.file.api.dsl

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.model.config.ConfigRef
import dev.museframework.dsl.api.util.CallSiteCapture
import dev.museframework.file.api.config.*
import dev.museframework.file.api.model.FileWriteMode
import dev.museframework.file.api.model.LocalFileMatcher
import dev.museframework.file.api.model.LocalSubsetList
import java.util.UUID
import java.util.concurrent.TimeUnit

@MuseDsl
abstract class FileComponentBuilder protected constructor(
    protected val connectionConfig: ConfigRef,
) {
    var docName: String = ""
    var docId: String = UUID.randomUUID().toString()
    var docDescription: String? = null
    var sourceFile: String? = null
    var sourceLine: Int? = null
    var sourceClass: String? = null

    protected fun sourceLocation(): SourceLocation {
        val callSite = CallSiteCapture.capture()
        return SourceLocation(
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
        )
    }

    protected data class SourceLocation(
        val sourceFile: String?,
        val sourceLine: Int?,
        val sourceClass: String?,
    )
}

@MuseDsl
class FileReadBuilder internal constructor(connectionConfig: ConfigRef) : FileComponentBuilder(connectionConfig) {
    var path: String = ""
    var lock: Boolean = false
    var timeBetweenSizeCheck: Long? = null
    var timeBetweenSizeCheckUnit: TimeUnit? = null
    var outputMimeType: String? = null
    var encoding: String? = null
    var targetConfig: TargetConfig? = null

    fun build(): FileReadConfig {
        require(path.isNotBlank()) { "File read path must not be blank" }
        require(timeBetweenSizeCheck == null || timeBetweenSizeCheck!! >= 0) {
            "timeBetweenSizeCheck must be greater than or equal to zero"
        }
        val location = sourceLocation()
        return FileReadConfig(
            docName = docName,
            docId = docId,
            docDescription = docDescription,
            sourceFile = location.sourceFile,
            sourceLine = location.sourceLine,
            sourceClass = location.sourceClass,
            connectionConfig = connectionConfig,
            path = path,
            lock = lock,
            timeBetweenSizeCheck = timeBetweenSizeCheck,
            timeBetweenSizeCheckUnit = timeBetweenSizeCheckUnit,
            outputMimeType = outputMimeType,
            encoding = encoding,
            targetConfig = targetConfig,
        )
    }
}

@MuseDsl
class FileWriteBuilder internal constructor(connectionConfig: ConfigRef) : FileComponentBuilder(connectionConfig) {
    var path: String = ""
    var content: String? = null
    var createParentDirectories: Boolean = true
    var lock: Boolean = false
    var mode: FileWriteMode = FileWriteMode.OVERWRITE

    fun build(): FileWriteConfig {
        require(path.isNotBlank()) { "File write path must not be blank" }
        val location = sourceLocation()
        return FileWriteConfig(
            docName = docName,
            docId = docId,
            docDescription = docDescription,
            sourceFile = location.sourceFile,
            sourceLine = location.sourceLine,
            sourceClass = location.sourceClass,
            connectionConfig = connectionConfig,
            path = path,
            content = content,
            createParentDirectories = createParentDirectories,
            lock = lock,
            mode = mode,
        )
    }
}

@MuseDsl
class FileListBuilder internal constructor(connectionConfig: ConfigRef) : FileComponentBuilder(connectionConfig) {
    var directoryPath: String = ""
    var recursive: Boolean = false
    var matcher: LocalFileMatcher? = null
    var timeBetweenSizeCheck: Long? = null
    var timeBetweenSizeCheckUnit: TimeUnit? = null
    var subset: LocalSubsetList? = null
    var targetConfig: TargetConfig? = null

    fun build(): FileListConfig {
        require(directoryPath.isNotBlank()) { "File list directoryPath must not be blank" }
        require(timeBetweenSizeCheck == null || timeBetweenSizeCheck!! >= 0) {
            "timeBetweenSizeCheck must be greater than or equal to zero"
        }
        val location = sourceLocation()
        return FileListConfig(
            docName = docName,
            docId = docId,
            docDescription = docDescription,
            sourceFile = location.sourceFile,
            sourceLine = location.sourceLine,
            sourceClass = location.sourceClass,
            connectionConfig = connectionConfig,
            directoryPath = directoryPath,
            recursive = recursive,
            matcher = matcher,
            timeBetweenSizeCheck = timeBetweenSizeCheck,
            timeBetweenSizeCheckUnit = timeBetweenSizeCheckUnit,
            subset = subset,
            targetConfig = targetConfig,
        )
    }
}

@MuseDsl
class FileCopyBuilder internal constructor(connectionConfig: ConfigRef) : FileComponentBuilder(connectionConfig) {
    var sourcePath: String = ""
    var targetPath: String = ""
    var createParentDirectories: Boolean = true
    var overwrite: Boolean = false
    var renameTo: String? = null

    fun build(): FileCopyConfig {
        require(sourcePath.isNotBlank()) { "File copy sourcePath must not be blank" }
        require(targetPath.isNotBlank()) { "File copy targetPath must not be blank" }
        val location = sourceLocation()
        return FileCopyConfig(
            docName = docName,
            docId = docId,
            docDescription = docDescription,
            sourceFile = location.sourceFile,
            sourceLine = location.sourceLine,
            sourceClass = location.sourceClass,
            connectionConfig = connectionConfig,
            sourcePath = sourcePath,
            targetPath = targetPath,
            createParentDirectories = createParentDirectories,
            overwrite = overwrite,
            renameTo = renameTo,
        )
    }
}

@MuseDsl
class FileMoveBuilder internal constructor(connectionConfig: ConfigRef) : FileComponentBuilder(connectionConfig) {
    var sourcePath: String = ""
    var targetPath: String = ""
    var createParentDirectories: Boolean = true
    var overwrite: Boolean = false
    var renameTo: String? = null

    fun build(): FileMoveConfig {
        require(sourcePath.isNotBlank()) { "File move sourcePath must not be blank" }
        require(targetPath.isNotBlank()) { "File move targetPath must not be blank" }
        val location = sourceLocation()
        return FileMoveConfig(
            docName = docName,
            docId = docId,
            docDescription = docDescription,
            sourceFile = location.sourceFile,
            sourceLine = location.sourceLine,
            sourceClass = location.sourceClass,
            connectionConfig = connectionConfig,
            sourcePath = sourcePath,
            targetPath = targetPath,
            createParentDirectories = createParentDirectories,
            overwrite = overwrite,
            renameTo = renameTo,
        )
    }
}

@MuseDsl
class FileDeleteBuilder internal constructor(connectionConfig: ConfigRef) : FileComponentBuilder(connectionConfig) {
    var path: String = ""

    fun build(): FileDeleteConfig {
        require(path.isNotBlank()) { "File delete path must not be blank" }
        val location = sourceLocation()
        return FileDeleteConfig(
            docName = docName,
            docId = docId,
            docDescription = docDescription,
            sourceFile = location.sourceFile,
            sourceLine = location.sourceLine,
            sourceClass = location.sourceClass,
            connectionConfig = connectionConfig,
            path = path,
        )
    }
}

@MuseDsl
class FileRenameBuilder internal constructor(connectionConfig: ConfigRef) : FileComponentBuilder(connectionConfig) {
    var path: String = ""
    var to: String = ""
    var overwrite: Boolean = false

    fun build(): FileRenameConfig {
        require(path.isNotBlank()) { "File rename path must not be blank" }
        require(to.isNotBlank()) { "File rename target name must not be blank" }
        val location = sourceLocation()
        return FileRenameConfig(
            docName = docName,
            docId = docId,
            docDescription = docDescription,
            sourceFile = location.sourceFile,
            sourceLine = location.sourceLine,
            sourceClass = location.sourceClass,
            connectionConfig = connectionConfig,
            path = path,
            to = to,
            overwrite = overwrite,
        )
    }
}

@MuseDsl
class FileCreateDirectoryBuilder internal constructor(connectionConfig: ConfigRef) : FileComponentBuilder(connectionConfig) {
    var directoryPath: String = ""

    fun build(): FileCreateDirectoryConfig {
        require(directoryPath.isNotBlank()) { "File create-directory path must not be blank" }
        val location = sourceLocation()
        return FileCreateDirectoryConfig(
            docName = docName,
            docId = docId,
            docDescription = docDescription,
            sourceFile = location.sourceFile,
            sourceLine = location.sourceLine,
            sourceClass = location.sourceClass,
            connectionConfig = connectionConfig,
            directoryPath = directoryPath,
        )
    }
}
