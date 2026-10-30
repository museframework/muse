package dev.museframework.sftp.api.dsl

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.util.CallSiteCapture
import dev.museframework.sftp.api.config.SftpConnectionRef
import dev.museframework.sftp.api.config.SftpCopyConfig
import dev.museframework.sftp.api.config.SftpCreateDirectoryConfig
import dev.museframework.sftp.api.config.SftpDeleteConfig
import dev.museframework.sftp.api.config.SftpListConfig
import dev.museframework.sftp.api.config.SftpMoveConfig
import dev.museframework.sftp.api.config.SftpReadConfig
import dev.museframework.sftp.api.config.SftpRenameConfig
import dev.museframework.sftp.api.config.SftpWriteConfig
import dev.museframework.sftp.api.model.SftpFileMatcher
import dev.museframework.sftp.api.model.SftpWriteMode
import java.util.UUID

@MuseDsl
abstract class SftpOperationBuilder(
    protected val connection: SftpConnectionRef,
) {
    var docName: String = ""
    var docId: String = UUID.randomUUID().toString()
    var docDescription: String? = null
    var sourceFile: String? = null
    var sourceLine: Int? = null
    var sourceClass: String? = null

    protected fun callSite(): CallSiteCapture.CallSite = CallSiteCapture.capture()
}

@MuseDsl
class SftpListBuilder internal constructor(connection: SftpConnectionRef) : SftpOperationBuilder(connection) {
    var directoryPath: String = "/"
    var recursive: Boolean = false
    var matcher: SftpFileMatcher = SftpFileMatcher()
    var targetConfig: TargetConfig? = null

    fun build(): SftpListConfig {
        val callSite = callSite()
        return SftpListConfig(
            docName = docName,
            docId = docId,
            docDescription = docDescription,
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
            connection = connection,
            directoryPath = directoryPath,
            recursive = recursive,
            matcher = matcher,
            targetConfig = targetConfig,
        )
    }
}

@MuseDsl
class SftpReadBuilder internal constructor(connection: SftpConnectionRef) : SftpOperationBuilder(connection) {
    var path: String = ""
    var targetConfig: TargetConfig? = null

    fun build(): SftpReadConfig {
        val callSite = callSite()
        return SftpReadConfig(
            docName = docName,
            docId = docId,
            docDescription = docDescription,
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
            connection = connection,
            path = path,
            targetConfig = targetConfig,
        )
    }
}

@MuseDsl
class SftpWriteBuilder internal constructor(connection: SftpConnectionRef) : SftpOperationBuilder(connection) {
    var path: String = ""
    var content: String? = null
    var createParentDirectories: Boolean = true
    var mode: SftpWriteMode = SftpWriteMode.OVERWRITE
    var modeExpression: String? = null

    fun build(): SftpWriteConfig {
        val callSite = callSite()
        return SftpWriteConfig(
            docName = docName,
            docId = docId,
            docDescription = docDescription,
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
            connection = connection,
            path = path,
            content = content,
            createParentDirectories = createParentDirectories,
            mode = mode,
            modeExpression = modeExpression,
        )
    }
}

@MuseDsl
class SftpCopyBuilder internal constructor(connection: SftpConnectionRef) : SftpOperationBuilder(connection) {
    var sourcePath: String = ""
    var targetDirectory: String = ""
    var createParentDirectories: Boolean = true
    var overwrite: Boolean = false
    var renameTo: String? = null

    fun build(): SftpCopyConfig {
        val callSite = callSite()
        return SftpCopyConfig(
            docName = docName,
            docId = docId,
            docDescription = docDescription,
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
            connection = connection,
            sourcePath = sourcePath,
            targetDirectory = targetDirectory,
            createParentDirectories = createParentDirectories,
            overwrite = overwrite,
            renameTo = renameTo,
        )
    }
}

@MuseDsl
class SftpMoveBuilder internal constructor(connection: SftpConnectionRef) : SftpOperationBuilder(connection) {
    var sourcePath: String = ""
    var targetDirectory: String = ""
    var createParentDirectories: Boolean = true
    var overwrite: Boolean = false
    var renameTo: String? = null

    fun build(): SftpMoveConfig {
        val callSite = callSite()
        return SftpMoveConfig(
            docName = docName,
            docId = docId,
            docDescription = docDescription,
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
            connection = connection,
            sourcePath = sourcePath,
            targetDirectory = targetDirectory,
            createParentDirectories = createParentDirectories,
            overwrite = overwrite,
            renameTo = renameTo,
        )
    }
}

@MuseDsl
class SftpDeleteBuilder internal constructor(connection: SftpConnectionRef) : SftpOperationBuilder(connection) {
    var path: String = ""

    fun build(): SftpDeleteConfig {
        val callSite = callSite()
        return SftpDeleteConfig(
            docName = docName,
            docId = docId,
            docDescription = docDescription,
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
            connection = connection,
            path = path,
        )
    }
}

@MuseDsl
class SftpRenameBuilder internal constructor(connection: SftpConnectionRef) : SftpOperationBuilder(connection) {
    var path: String = ""
    var newName: String = ""
    var overwrite: Boolean = false

    fun build(): SftpRenameConfig {
        val callSite = callSite()
        return SftpRenameConfig(
            docName = docName,
            docId = docId,
            docDescription = docDescription,
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
            connection = connection,
            path = path,
            newName = newName,
            overwrite = overwrite,
        )
    }
}

@MuseDsl
class SftpCreateDirectoryBuilder internal constructor(connection: SftpConnectionRef) : SftpOperationBuilder(connection) {
    var directoryPath: String = ""

    fun build(): SftpCreateDirectoryConfig {
        val callSite = callSite()
        return SftpCreateDirectoryConfig(
            docName = docName,
            docId = docId,
            docDescription = docDescription,
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
            connection = connection,
            directoryPath = directoryPath,
        )
    }
}
