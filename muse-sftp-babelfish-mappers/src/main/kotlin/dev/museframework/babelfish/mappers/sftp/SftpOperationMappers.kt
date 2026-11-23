package dev.museframework.babelfish.mappers.sftp

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.buildCodeBlock
import com.squareup.kotlinpoet.joinToCode
import dev.museframework.babelfish.api.ElementMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.babelfish.emitter.kotlinStringLiteral
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.config.TargetConfig
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
import dev.museframework.sftp.api.model.SftpTransferRequest
import dev.museframework.sftp.api.model.SftpWriteMode
import java.util.UUID

object SftpReadMapper : ElementMapper<SftpReadConfig> {
    override fun map(
        element: RawElement,
        ctx: MappingContext,
    ): SftpReadConfig =
        SftpReadConfig(
            docName = element.attrs["doc:name"].orEmpty(),
            docId = element.docIdOrRandom(),
            connection = SftpConnectionRef.ByName(element.requireAttr("config-ref", element.location)),
            path = element.requireAttr("path", element.location),
            targetConfig = element.mapTargetConfig(),
        )
}

object SftpWriteMapper : ElementMapper<SftpWriteConfig> {
    override fun map(
        element: RawElement,
        ctx: MappingContext,
    ): SftpWriteConfig {
        val rawMode = element.attrs["mode"]
        val modeExpression = rawMode?.takeIf { it.trim().startsWith("#[") && it.trim().endsWith("]") }
        return SftpWriteConfig(
            docName = element.attrs["doc:name"].orEmpty(),
            docId = element.docIdOrRandom(),
            connection = SftpConnectionRef.ByName(element.requireAttr("config-ref", element.location)),
            path = element.requireAttr("path", element.location),
            content = element.childText("content"),
            createParentDirectories =
                element.attrs["createParentDirectories"]?.toBooleanStrictOrNull() ?: true,
            mode = if (modeExpression == null) rawMode?.toSftpEnum<SftpWriteMode>() ?: SftpWriteMode.OVERWRITE else SftpWriteMode.OVERWRITE,
            modeExpression = modeExpression,
        )
    }
}

object SftpListMapper : ElementMapper<SftpListConfig> {
    override fun map(
        element: RawElement,
        ctx: MappingContext,
    ): SftpListConfig =
        SftpListConfig(
            docName = element.attrs["doc:name"].orEmpty(),
            docId = element.docIdOrRandom(),
            connection = SftpConnectionRef.ByName(element.requireAttr("config-ref", element.location)),
            directoryPath =
                element.attrs["directoryPath"]
                    ?: element.attrs["dir"]
                    ?: element.requireAttr("directoryPath", element.location),
            recursive = element.attrs["recursive"]?.toBooleanStrictOrNull() ?: false,
            matcher = mapSftpMatcher(element, ctx),
            targetConfig = element.mapTargetConfig(),
        )
}

object SftpCopyMapper : ElementMapper<SftpCopyConfig> {
    override fun map(
        element: RawElement,
        ctx: MappingContext,
    ): SftpCopyConfig {
        val transfer = mapTransferRequest(element)
        return SftpCopyConfig(
            docName = element.attrs["doc:name"].orEmpty(),
            docId = element.docIdOrRandom(),
            connection = SftpConnectionRef.ByName(element.requireAttr("config-ref", element.location)),
            sourcePath = transfer.sourcePath,
            targetDirectory = transfer.targetDirectory,
            createParentDirectories = transfer.createParentDirectories,
            overwrite = transfer.overwrite,
            renameTo = transfer.renameTo,
        )
    }
}

object SftpMoveMapper : ElementMapper<SftpMoveConfig> {
    override fun map(
        element: RawElement,
        ctx: MappingContext,
    ): SftpMoveConfig {
        val transfer = mapTransferRequest(element)
        return SftpMoveConfig(
            docName = element.attrs["doc:name"].orEmpty(),
            docId = element.docIdOrRandom(),
            connection = SftpConnectionRef.ByName(element.requireAttr("config-ref", element.location)),
            sourcePath = transfer.sourcePath,
            targetDirectory = transfer.targetDirectory,
            createParentDirectories = transfer.createParentDirectories,
            overwrite = transfer.overwrite,
            renameTo = transfer.renameTo,
        )
    }
}

object SftpDeleteMapper : ElementMapper<SftpDeleteConfig> {
    override fun map(
        element: RawElement,
        ctx: MappingContext,
    ): SftpDeleteConfig =
        SftpDeleteConfig(
            docName = element.attrs["doc:name"].orEmpty(),
            docId = element.docIdOrRandom(),
            connection = SftpConnectionRef.ByName(element.requireAttr("config-ref", element.location)),
            path = element.requireAttr("path", element.location),
        )
}

object SftpRenameMapper : ElementMapper<SftpRenameConfig> {
    override fun map(
        element: RawElement,
        ctx: MappingContext,
    ): SftpRenameConfig =
        SftpRenameConfig(
            docName = element.attrs["doc:name"].orEmpty(),
            docId = element.docIdOrRandom(),
            connection = SftpConnectionRef.ByName(element.requireAttr("config-ref", element.location)),
            path = element.requireAttr("path", element.location),
            newName =
                element.attrs["newName"]
                    ?: element.requireAttr("to", element.location),
            overwrite = element.attrs["overwrite"]?.toBooleanStrictOrNull() ?: false,
        )
}

object SftpCreateDirectoryMapper : ElementMapper<SftpCreateDirectoryConfig> {
    override fun map(
        element: RawElement,
        ctx: MappingContext,
    ): SftpCreateDirectoryConfig =
        SftpCreateDirectoryConfig(
            docName = element.attrs["doc:name"].orEmpty(),
            docId = element.docIdOrRandom(),
            connection = SftpConnectionRef.ByName(element.requireAttr("config-ref", element.location)),
            directoryPath = element.requireAttr("directoryPath", element.location),
        )
}

object SftpReadCodeEmitter : ElementCodeEmitter<SftpReadConfig> {
    override fun emit(element: SftpReadConfig, ctx: CodegenContext): CodeBlock =
        sftpOperation("sftpRead", element.connection, element.docId) {
            addString("path", element.path)
            addTargetConfig(element.targetConfig)
        }
}

object SftpWriteCodeEmitter : ElementCodeEmitter<SftpWriteConfig> {
    override fun emit(element: SftpWriteConfig, ctx: CodegenContext): CodeBlock =
        sftpOperation("sftpWrite", element.connection, element.docId) {
            addString("path", element.path)
            addString("content", element.content)
            addString("modeExpression", element.modeExpression)
            addNonDefaultBoolean("createParentDirectories", element.createParentDirectories, default = true)
            if (element.mode != SftpWriteMode.OVERWRITE) {
                addStatement("mode = %T.%N", SftpWriteMode::class, element.mode.name)
            }
        }
}

object SftpListCodeEmitter : ElementCodeEmitter<SftpListConfig> {
    override fun emit(element: SftpListConfig, ctx: CodegenContext): CodeBlock =
        sftpOperation("sftpList", element.connection, element.docId) {
            if (element.directoryPath != "/") addString("directoryPath", element.directoryPath)
            addNonDefaultBoolean("recursive", element.recursive, default = false)
            addMatcher(element.matcher, SftpFileMatcher())
            addTargetConfig(element.targetConfig)
        }
}

object SftpCopyCodeEmitter : ElementCodeEmitter<SftpCopyConfig> {
    override fun emit(element: SftpCopyConfig, ctx: CodegenContext): CodeBlock =
        sftpOperation("sftpCopy", element.connection, element.docId) {
            addTransfer(element.transfer)
        }
}

object SftpMoveCodeEmitter : ElementCodeEmitter<SftpMoveConfig> {
    override fun emit(element: SftpMoveConfig, ctx: CodegenContext): CodeBlock =
        sftpOperation("sftpMove", element.connection, element.docId) {
            addTransfer(element.transfer)
        }
}

object SftpDeleteCodeEmitter : ElementCodeEmitter<SftpDeleteConfig> {
    override fun emit(element: SftpDeleteConfig, ctx: CodegenContext): CodeBlock =
        sftpOperation("sftpDelete", element.connection, element.docId) {
            addString("path", element.path)
        }
}

object SftpRenameCodeEmitter : ElementCodeEmitter<SftpRenameConfig> {
    override fun emit(element: SftpRenameConfig, ctx: CodegenContext): CodeBlock =
        sftpOperation("sftpRename", element.connection, element.docId) {
            addString("path", element.path)
            addString("newName", element.newName)
            addNonDefaultBoolean("overwrite", element.overwrite, default = false)
        }
}

object SftpCreateDirectoryCodeEmitter : ElementCodeEmitter<SftpCreateDirectoryConfig> {
    override fun emit(element: SftpCreateDirectoryConfig, ctx: CodegenContext): CodeBlock =
        sftpOperation("sftpCreateDirectory", element.connection, element.docId) {
            addString("directoryPath", element.directoryPath)
        }
}

private fun mapTransferRequest(element: RawElement): SftpTransferRequest =
    SftpTransferRequest(
        sourcePath = element.requireAttr("sourcePath", element.location),
        targetDirectory =
            element.attrs["targetDirectory"]
                ?: element.requireAttr("targetPath", element.location),
        createParentDirectories =
            element.attrs["createParentDirectories"]?.toBooleanStrictOrNull() ?: true,
        overwrite = element.attrs["overwrite"]?.toBooleanStrictOrNull() ?: false,
        renameTo = element.attrs["renameTo"],
    )

private fun sftpOperation(
    name: String,
    connection: SftpConnectionRef,
    docId: String,
    body: CodeBlock.Builder.() -> Unit,
): CodeBlock = buildCodeBlock {
    beginControlFlow("%M(%M(%L))", sftpDsl(name), sftpDsl("sftpConnection"), connectionName(connection))
    addString("docId", docId)
    body()
    endControlFlow()
}

private fun RawElement.docIdOrRandom(): String =
    attrs["doc:id"] ?: UUID.randomUUID().toString()

private fun CodeBlock.Builder.addTransfer(transfer: SftpTransferRequest) {
    addString("sourcePath", transfer.sourcePath)
    addString("targetDirectory", transfer.targetDirectory)
    addNonDefaultBoolean("createParentDirectories", transfer.createParentDirectories, default = true)
    addNonDefaultBoolean("overwrite", transfer.overwrite, default = false)
    addString("renameTo", transfer.renameTo)
}

private fun CodeBlock.Builder.addTargetConfig(target: TargetConfig?) {
    if (target == null) return
    val arguments = listOfNotNull(target.target, target.targetValue).map(::kotlinStringLiteral).joinToCode()
    addStatement("targetConfig = %T(%L)", TargetConfig::class, arguments)
}
