package dev.museframework.babelfish.mappers.file

import dev.museframework.babelfish.api.ElementMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.model.config.ConfigRef
import dev.museframework.file.api.config.FileCopyConfig
import dev.museframework.file.api.config.FileCreateDirectoryConfig
import dev.museframework.file.api.config.FileDeleteConfig
import dev.museframework.file.api.config.FileListConfig
import dev.museframework.file.api.config.FileMoveConfig
import dev.museframework.file.api.config.FileReadConfig
import dev.museframework.file.api.config.FileRenameConfig
import dev.museframework.file.api.config.FileWriteConfig
import dev.museframework.file.api.model.FileWriteMode

object FileReadMapper : ElementMapper<FileReadConfig> {
    override fun map(
        element: RawElement,
        ctx: MappingContext,
    ): FileReadConfig =
        FileReadConfig(
            docName = element.attrs["doc:name"].orEmpty(),
            connectionConfig = ConfigRef.ByName(element.requireAttr("config-ref", element.location)),
            path = element.requireAttr("path", element.location),
            lock = element.attrs["lock"]?.toBooleanStrictOrNull() ?: false,
            timeBetweenSizeCheck = element.attrs["timeBetweenSizeCheck"]?.toLongOrNull(),
            timeBetweenSizeCheckUnit = element.attrs["timeBetweenSizeCheckUnit"]?.toTimeUnit(),
            outputMimeType = element.attrs["outputMimeType"],
            encoding = element.attrs["outputEncoding"] ?: element.attrs["encoding"],
            targetConfig = element.attrs["target"]?.let { TargetConfig(it, element.attrs["targetValue"]) },
        )
}

object FileWriteMapper : ElementMapper<FileWriteConfig> {
    override fun map(
        element: RawElement,
        ctx: MappingContext,
    ): FileWriteConfig =
        FileWriteConfig(
            docName = element.attrs["doc:name"].orEmpty(),
            connectionConfig = ConfigRef.ByName(element.requireAttr("config-ref", element.location)),
            path = element.requireAttr("path", element.location),
            content = element.childText("content"),
            createParentDirectories =
                element.attrs["createParentDirectories"]?.toBooleanStrictOrNull() ?: true,
            lock = element.attrs["lock"]?.toBooleanStrictOrNull() ?: false,
            mode = element.attrs["mode"]?.toFileEnum<FileWriteMode>() ?: FileWriteMode.OVERWRITE,
        )
}

object FileListMapper : ElementMapper<FileListConfig> {
    override fun map(
        element: RawElement,
        ctx: MappingContext,
    ): FileListConfig =
        FileListConfig(
            docName = element.attrs["doc:name"].orEmpty(),
            connectionConfig = ConfigRef.ByName(element.requireAttr("config-ref", element.location)),
            directoryPath =
                element.attrs["directoryPath"]
                    ?: element.attrs["dir"]
                    ?: element.requireAttr("directoryPath", element.location),
            recursive = element.attrs["recursive"]?.toBooleanStrictOrNull() ?: false,
            matcher = resolveFileMatcher(element, ctx),
            timeBetweenSizeCheck = element.attrs["timeBetweenSizeCheck"]?.toLongOrNull(),
            timeBetweenSizeCheckUnit = element.attrs["timeBetweenSizeCheckUnit"]?.toTimeUnit(),
            subset = mapSubset(element),
        )
}

object FileCopyMapper : ElementMapper<FileCopyConfig> {
    override fun map(
        element: RawElement,
        ctx: MappingContext,
    ): FileCopyConfig =
        FileCopyConfig(
            docName = element.attrs["doc:name"].orEmpty(),
            connectionConfig = ConfigRef.ByName(element.requireAttr("config-ref", element.location)),
            sourcePath = element.requireAttr("sourcePath", element.location),
            targetPath = element.requireAttr("targetPath", element.location),
            createParentDirectories =
                element.attrs["createParentDirectories"]?.toBooleanStrictOrNull() ?: true,
            overwrite = element.attrs["overwrite"]?.toBooleanStrictOrNull() ?: false,
            renameTo = element.attrs["renameTo"],
        )
}

object FileMoveMapper : ElementMapper<FileMoveConfig> {
    override fun map(
        element: RawElement,
        ctx: MappingContext,
    ): FileMoveConfig =
        FileMoveConfig(
            docName = element.attrs["doc:name"].orEmpty(),
            connectionConfig = ConfigRef.ByName(element.requireAttr("config-ref", element.location)),
            sourcePath = element.requireAttr("sourcePath", element.location),
            targetPath = element.requireAttr("targetPath", element.location),
            createParentDirectories =
                element.attrs["createParentDirectories"]?.toBooleanStrictOrNull() ?: true,
            overwrite = element.attrs["overwrite"]?.toBooleanStrictOrNull() ?: false,
            renameTo = element.attrs["renameTo"],
        )
}

object FileDeleteMapper : ElementMapper<FileDeleteConfig> {
    override fun map(
        element: RawElement,
        ctx: MappingContext,
    ): FileDeleteConfig =
        FileDeleteConfig(
            docName = element.attrs["doc:name"].orEmpty(),
            connectionConfig = ConfigRef.ByName(element.requireAttr("config-ref", element.location)),
            path = element.requireAttr("path", element.location),
        )
}

object FileRenameMapper : ElementMapper<FileRenameConfig> {
    override fun map(
        element: RawElement,
        ctx: MappingContext,
    ): FileRenameConfig =
        FileRenameConfig(
            docName = element.attrs["doc:name"].orEmpty(),
            connectionConfig = ConfigRef.ByName(element.requireAttr("config-ref", element.location)),
            path = element.requireAttr("path", element.location),
            to = element.requireAttr("to", element.location),
            overwrite = element.attrs["overwrite"]?.toBooleanStrictOrNull() ?: false,
        )
}

object FileCreateDirectoryMapper : ElementMapper<FileCreateDirectoryConfig> {
    override fun map(
        element: RawElement,
        ctx: MappingContext,
    ): FileCreateDirectoryConfig =
        FileCreateDirectoryConfig(
            docName = element.attrs["doc:name"].orEmpty(),
            connectionConfig = ConfigRef.ByName(element.requireAttr("config-ref", element.location)),
            directoryPath = element.requireAttr("directoryPath", element.location),
        )
}

object FileReadCodeEmitter : ElementCodeEmitter<FileReadConfig> {
    override fun emit(
        element: FileReadConfig,
        ctx: CodegenContext,
    ): String {
        ctx.addImport("dev.museframework.file.api.dsl.fileConnection")
        ctx.addImport("dev.museframework.file.api.dsl.fileRead")
        ctx.addImport("java.util.concurrent.TimeUnit")
        return buildString {
            appendLine("fileRead(${connectionRefExpression(element.connectionConfig, ctx)}) {")
            appendLine("    path = \"${ctx.escapeKotlinString(element.path)}\"")
            appendNonDefaultBoolean("lock", element.lock, default = false)
            appendOptionalLong("timeBetweenSizeCheck", element.timeBetweenSizeCheck)
            appendTimeUnit("timeBetweenSizeCheckUnit", element.timeBetweenSizeCheckUnit)
            appendOptionalString("outputMimeType", element.outputMimeType, ctx)
            appendOptionalString("encoding", element.encoding, ctx)
            element.targetConfig?.let { target ->
                ctx.addImport("dev.museframework.dsl.api.config.TargetConfig")
                append("    targetConfig = TargetConfig(\"${ctx.escapeKotlinString(target.target)}\"")
                target.targetValue?.let { append(", \"${ctx.escapeKotlinString(it)}\"") }
                appendLine(")")
            }
            append("}")
        }
    }
}

object FileWriteCodeEmitter : ElementCodeEmitter<FileWriteConfig> {
    override fun emit(
        element: FileWriteConfig,
        ctx: CodegenContext,
    ): String {
        ctx.addImport("dev.museframework.file.api.dsl.fileConnection")
        ctx.addImport("dev.museframework.file.api.dsl.fileWrite")
        ctx.addImport("dev.museframework.file.api.model.FileWriteMode")
        return buildString {
            appendLine("fileWrite(${connectionRefExpression(element.connectionConfig, ctx)}) {")
            appendLine("    path = \"${ctx.escapeKotlinString(element.path)}\"")
            appendOptionalString("content", element.content, ctx)
            appendNonDefaultBoolean("createParentDirectories", element.createParentDirectories, default = true)
            appendNonDefaultBoolean("lock", element.lock, default = false)
            if (element.mode != FileWriteMode.OVERWRITE) {
                appendLine("    mode = FileWriteMode.${element.mode}")
            }
            append("}")
        }
    }
}

object FileListCodeEmitter : ElementCodeEmitter<FileListConfig> {
    override fun emit(
        element: FileListConfig,
        ctx: CodegenContext,
    ): String {
        ctx.addImport("dev.museframework.file.api.dsl.fileConnection")
        ctx.addImport("dev.museframework.file.api.dsl.fileList")
        ctx.addImport("java.util.concurrent.TimeUnit")
        return buildString {
            appendLine("fileList(${connectionRefExpression(element.connectionConfig, ctx)}) {")
            appendLine("    directoryPath = \"${ctx.escapeKotlinString(element.directoryPath)}\"")
            appendNonDefaultBoolean("recursive", element.recursive, default = false)
            appendOptionalLong("timeBetweenSizeCheck", element.timeBetweenSizeCheck)
            appendTimeUnit("timeBetweenSizeCheckUnit", element.timeBetweenSizeCheckUnit)
            appendLocalFileMatcherAssignment("matcher", element.matcher, ctx)
            appendSubset(element.subset, ctx)
            append("}")
        }
    }
}

object FileCopyCodeEmitter : ElementCodeEmitter<FileCopyConfig> {
    override fun emit(
        element: FileCopyConfig,
        ctx: CodegenContext,
    ): String = emitTransfer("fileCopy", element.connectionConfig, element.sourcePath, element.targetPath,
        element.createParentDirectories, element.overwrite, element.renameTo, ctx)
}

object FileMoveCodeEmitter : ElementCodeEmitter<FileMoveConfig> {
    override fun emit(
        element: FileMoveConfig,
        ctx: CodegenContext,
    ): String = emitTransfer("fileMove", element.connectionConfig, element.sourcePath, element.targetPath,
        element.createParentDirectories, element.overwrite, element.renameTo, ctx)
}

object FileDeleteCodeEmitter : ElementCodeEmitter<FileDeleteConfig> {
    override fun emit(
        element: FileDeleteConfig,
        ctx: CodegenContext,
    ): String {
        ctx.addImport("dev.museframework.file.api.dsl.fileConnection")
        ctx.addImport("dev.museframework.file.api.dsl.fileDelete")
        return buildString {
            appendLine("fileDelete(${connectionRefExpression(element.connectionConfig, ctx)}) {")
            appendLine("    path = \"${ctx.escapeKotlinString(element.path)}\"")
            append("}")
        }
    }
}

object FileRenameCodeEmitter : ElementCodeEmitter<FileRenameConfig> {
    override fun emit(
        element: FileRenameConfig,
        ctx: CodegenContext,
    ): String {
        ctx.addImport("dev.museframework.file.api.dsl.fileConnection")
        ctx.addImport("dev.museframework.file.api.dsl.fileRename")
        return buildString {
            appendLine("fileRename(${connectionRefExpression(element.connectionConfig, ctx)}) {")
            appendLine("    path = \"${ctx.escapeKotlinString(element.path)}\"")
            appendLine("    to = \"${ctx.escapeKotlinString(element.to)}\"")
            appendNonDefaultBoolean("overwrite", element.overwrite, default = false)
            append("}")
        }
    }
}

object FileCreateDirectoryCodeEmitter : ElementCodeEmitter<FileCreateDirectoryConfig> {
    override fun emit(
        element: FileCreateDirectoryConfig,
        ctx: CodegenContext,
    ): String {
        ctx.addImport("dev.museframework.file.api.dsl.fileConnection")
        ctx.addImport("dev.museframework.file.api.dsl.fileCreateDirectory")
        return buildString {
            appendLine("fileCreateDirectory(${connectionRefExpression(element.connectionConfig, ctx)}) {")
            appendLine("    directoryPath = \"${ctx.escapeKotlinString(element.directoryPath)}\"")
            append("}")
        }
    }
}

private fun emitTransfer(
    dslName: String,
    connectionConfig: ConfigRef,
    sourcePath: String,
    targetPath: String,
    createParentDirectories: Boolean,
    overwrite: Boolean,
    renameTo: String?,
    ctx: CodegenContext,
): String {
    ctx.addImport("dev.museframework.file.api.dsl.fileConnection")
    ctx.addImport("dev.museframework.file.api.dsl.$dslName")
    return buildString {
        appendLine("$dslName(${connectionRefExpression(connectionConfig, ctx)}) {")
        appendLine("    sourcePath = \"${ctx.escapeKotlinString(sourcePath)}\"")
        appendLine("    targetPath = \"${ctx.escapeKotlinString(targetPath)}\"")
        appendNonDefaultBoolean("createParentDirectories", createParentDirectories, default = true)
        appendNonDefaultBoolean("overwrite", overwrite, default = false)
        appendOptionalString("renameTo", renameTo, ctx)
        append("}")
    }
}
