package dev.museframework.babelfish.mappers.file

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
    override fun emit(element: FileReadConfig, ctx: CodegenContext): CodeBlock =
        fileOperation("fileRead", element.connectionConfig) {
            addString("path", element.path)
            addNonDefaultBoolean("lock", element.lock, default = false)
            addLong("timeBetweenSizeCheck", element.timeBetweenSizeCheck)
            addTimeUnit("timeBetweenSizeCheckUnit", element.timeBetweenSizeCheckUnit)
            addString("outputMimeType", element.outputMimeType)
            addString("encoding", element.encoding)
            element.targetConfig?.let { target ->
                val arguments = listOfNotNull(target.target, target.targetValue).map(::kotlinStringLiteral).joinToCode()
                addStatement("targetConfig = %T(%L)", TargetConfig::class, arguments)
            }
        }
}

object FileWriteCodeEmitter : ElementCodeEmitter<FileWriteConfig> {
    override fun emit(element: FileWriteConfig, ctx: CodegenContext): CodeBlock =
        fileOperation("fileWrite", element.connectionConfig) {
            addString("path", element.path)
            addString("content", element.content)
            addNonDefaultBoolean("createParentDirectories", element.createParentDirectories, default = true)
            addNonDefaultBoolean("lock", element.lock, default = false)
            if (element.mode != FileWriteMode.OVERWRITE) {
                addStatement("mode = %T.%N", FileWriteMode::class, element.mode.name)
            }
        }
}

object FileListCodeEmitter : ElementCodeEmitter<FileListConfig> {
    override fun emit(element: FileListConfig, ctx: CodegenContext): CodeBlock =
        fileOperation("fileList", element.connectionConfig) {
            addString("directoryPath", element.directoryPath)
            addNonDefaultBoolean("recursive", element.recursive, default = false)
            addLong("timeBetweenSizeCheck", element.timeBetweenSizeCheck)
            addTimeUnit("timeBetweenSizeCheckUnit", element.timeBetweenSizeCheckUnit)
            addMatcher(element.matcher)
            addSubset(element.subset)
        }
}

object FileCopyCodeEmitter : ElementCodeEmitter<FileCopyConfig> {
    override fun emit(element: FileCopyConfig, ctx: CodegenContext): CodeBlock =
        fileOperation("fileCopy", element.connectionConfig) {
            addTransferOptions(
                element.sourcePath, element.targetPath, element.createParentDirectories, element.overwrite, element.renameTo,
            )
        }
}

object FileMoveCodeEmitter : ElementCodeEmitter<FileMoveConfig> {
    override fun emit(element: FileMoveConfig, ctx: CodegenContext): CodeBlock =
        fileOperation("fileMove", element.connectionConfig) {
            addTransferOptions(
                element.sourcePath, element.targetPath, element.createParentDirectories, element.overwrite, element.renameTo,
            )
        }
}

object FileDeleteCodeEmitter : ElementCodeEmitter<FileDeleteConfig> {
    override fun emit(element: FileDeleteConfig, ctx: CodegenContext): CodeBlock =
        fileOperation("fileDelete", element.connectionConfig) {
            addString("path", element.path)
        }
}

object FileRenameCodeEmitter : ElementCodeEmitter<FileRenameConfig> {
    override fun emit(element: FileRenameConfig, ctx: CodegenContext): CodeBlock =
        fileOperation("fileRename", element.connectionConfig) {
            addString("path", element.path)
            addString("to", element.to)
            addNonDefaultBoolean("overwrite", element.overwrite, default = false)
        }
}

object FileCreateDirectoryCodeEmitter : ElementCodeEmitter<FileCreateDirectoryConfig> {
    override fun emit(element: FileCreateDirectoryConfig, ctx: CodegenContext): CodeBlock =
        fileOperation("fileCreateDirectory", element.connectionConfig) {
            addString("directoryPath", element.directoryPath)
        }
}

private fun fileOperation(
    name: String,
    connectionConfig: ConfigRef,
    options: CodeBlock.Builder.() -> Unit,
): CodeBlock = buildCodeBlock {
    val ref = CodeBlock.of("%M(%L)", fileDsl("fileConnection"), connectionName(connectionConfig))
    beginControlFlow("%M(%L)", fileDsl(name), ref)
    options()
    endControlFlow()
}

private fun CodeBlock.Builder.addTransferOptions(
    sourcePath: String,
    targetPath: String,
    createParentDirectories: Boolean,
    overwrite: Boolean,
    renameTo: String?,
) {
    addString("sourcePath", sourcePath)
    addString("targetPath", targetPath)
    addNonDefaultBoolean("createParentDirectories", createParentDirectories, default = true)
    addNonDefaultBoolean("overwrite", overwrite, default = false)
    addString("renameTo", renameTo)
}
