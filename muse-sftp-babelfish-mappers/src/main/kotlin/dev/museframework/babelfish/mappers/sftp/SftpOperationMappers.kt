package dev.museframework.babelfish.mappers.sftp

import dev.museframework.babelfish.api.ElementMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
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

object SftpReadMapper : ElementMapper<SftpReadConfig> {
    override fun map(
        element: RawElement,
        ctx: MappingContext,
    ): SftpReadConfig =
        SftpReadConfig(
            docName = element.attrs["doc:name"].orEmpty(),
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
            connection = SftpConnectionRef.ByName(element.requireAttr("config-ref", element.location)),
            directoryPath = element.requireAttr("directoryPath", element.location),
        )
}

object SftpReadCodeEmitter : ElementCodeEmitter<SftpReadConfig> {
    override fun emit(
        element: SftpReadConfig,
        ctx: CodegenContext,
    ): String {
        ctx.addImport("dev.museframework.sftp.api.dsl.sftpConnection")
        ctx.addImport("dev.museframework.sftp.api.dsl.sftpRead")
        return buildString {
            appendLine("sftpRead(${connectionRefExpression(element.connection, ctx)}) {")
            appendLine("    path = \"${ctx.escapeKotlinString(element.path)}\"")
            appendTargetConfig(element.targetConfig, ctx)
            append("}")
        }
    }
}

object SftpWriteCodeEmitter : ElementCodeEmitter<SftpWriteConfig> {
    override fun emit(
        element: SftpWriteConfig,
        ctx: CodegenContext,
    ): String {
        ctx.addImport("dev.museframework.sftp.api.dsl.sftpConnection")
        ctx.addImport("dev.museframework.sftp.api.dsl.sftpWrite")
        ctx.addImport("dev.museframework.sftp.api.model.SftpWriteMode")
        return buildString {
            appendLine("sftpWrite(${connectionRefExpression(element.connection, ctx)}) {")
            appendLine("    path = \"${ctx.escapeKotlinString(element.path)}\"")
            appendOptionalString("content", element.content, ctx)
            appendOptionalString("modeExpression", element.modeExpression, ctx)
            appendNonDefaultBoolean("createParentDirectories", element.createParentDirectories, default = true)
            if (element.mode != SftpWriteMode.OVERWRITE) {
                appendLine("    mode = SftpWriteMode.${element.mode}")
            }
            append("}")
        }
    }
}

object SftpListCodeEmitter : ElementCodeEmitter<SftpListConfig> {
    override fun emit(
        element: SftpListConfig,
        ctx: CodegenContext,
    ): String {
        ctx.addImport("dev.museframework.sftp.api.dsl.sftpConnection")
        ctx.addImport("dev.museframework.sftp.api.dsl.sftpList")
        return buildString {
            appendLine("sftpList(${connectionRefExpression(element.connection, ctx)}) {")
            appendNonDefaultString("directoryPath", element.directoryPath, "/", ctx)
            appendNonDefaultBoolean("recursive", element.recursive, default = false)
            appendSftpMatcherAssignment("matcher", element.matcher, SftpFileMatcher(), ctx)
            appendTargetConfig(element.targetConfig, ctx)
            append("}")
        }
    }
}

object SftpCopyCodeEmitter : ElementCodeEmitter<SftpCopyConfig> {
    override fun emit(
        element: SftpCopyConfig,
        ctx: CodegenContext,
    ): String = emitTransfer("sftpCopy", element.connection, element.transfer, ctx)
}

object SftpMoveCodeEmitter : ElementCodeEmitter<SftpMoveConfig> {
    override fun emit(
        element: SftpMoveConfig,
        ctx: CodegenContext,
    ): String = emitTransfer("sftpMove", element.connection, element.transfer, ctx)
}

object SftpDeleteCodeEmitter : ElementCodeEmitter<SftpDeleteConfig> {
    override fun emit(
        element: SftpDeleteConfig,
        ctx: CodegenContext,
    ): String {
        ctx.addImport("dev.museframework.sftp.api.dsl.sftpConnection")
        ctx.addImport("dev.museframework.sftp.api.dsl.sftpDelete")
        return buildString {
            appendLine("sftpDelete(${connectionRefExpression(element.connection, ctx)}) {")
            appendLine("    path = \"${ctx.escapeKotlinString(element.path)}\"")
            append("}")
        }
    }
}

object SftpRenameCodeEmitter : ElementCodeEmitter<SftpRenameConfig> {
    override fun emit(
        element: SftpRenameConfig,
        ctx: CodegenContext,
    ): String {
        ctx.addImport("dev.museframework.sftp.api.dsl.sftpConnection")
        ctx.addImport("dev.museframework.sftp.api.dsl.sftpRename")
        return buildString {
            appendLine("sftpRename(${connectionRefExpression(element.connection, ctx)}) {")
            appendLine("    path = \"${ctx.escapeKotlinString(element.path)}\"")
            appendLine("    newName = \"${ctx.escapeKotlinString(element.newName)}\"")
            appendNonDefaultBoolean("overwrite", element.overwrite, default = false)
            append("}")
        }
    }
}

object SftpCreateDirectoryCodeEmitter : ElementCodeEmitter<SftpCreateDirectoryConfig> {
    override fun emit(
        element: SftpCreateDirectoryConfig,
        ctx: CodegenContext,
    ): String {
        ctx.addImport("dev.museframework.sftp.api.dsl.sftpConnection")
        ctx.addImport("dev.museframework.sftp.api.dsl.sftpCreateDirectory")
        return buildString {
            appendLine("sftpCreateDirectory(${connectionRefExpression(element.connection, ctx)}) {")
            appendLine("    directoryPath = \"${ctx.escapeKotlinString(element.directoryPath)}\"")
            append("}")
        }
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

private fun emitTransfer(
    dslName: String,
    connection: SftpConnectionRef,
    transfer: SftpTransferRequest,
    ctx: CodegenContext,
): String {
    ctx.addImport("dev.museframework.sftp.api.dsl.sftpConnection")
    ctx.addImport("dev.museframework.sftp.api.dsl.$dslName")
    return buildString {
        appendLine("$dslName(${connectionRefExpression(connection, ctx)}) {")
        appendLine("    sourcePath = \"${ctx.escapeKotlinString(transfer.sourcePath)}\"")
        appendLine("    targetDirectory = \"${ctx.escapeKotlinString(transfer.targetDirectory)}\"")
        appendNonDefaultBoolean("createParentDirectories", transfer.createParentDirectories, default = true)
        appendNonDefaultBoolean("overwrite", transfer.overwrite, default = false)
        appendOptionalString("renameTo", transfer.renameTo, ctx)
        append("}")
    }
}
