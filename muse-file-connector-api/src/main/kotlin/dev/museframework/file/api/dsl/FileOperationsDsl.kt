package dev.museframework.file.api.dsl

import dev.museframework.dsl.api.dsl.PipelineOwner
import dev.museframework.dsl.api.model.config.ConfigRef
import dev.museframework.file.api.config.FileConnectionConfig

fun PipelineOwner.fileRead(connectionConfig: ConfigRef, block: FileReadBuilder.() -> Unit) {
    processor(FileReadBuilder(connectionConfig).apply(block).build())
}

fun PipelineOwner.fileRead(connectionConfig: FileConnectionConfig, block: FileReadBuilder.() -> Unit) =
    fileRead(ConfigRef.ByReference(connectionConfig), block)

fun PipelineOwner.fileWrite(connectionConfig: ConfigRef, block: FileWriteBuilder.() -> Unit) {
    processor(FileWriteBuilder(connectionConfig).apply(block).build())
}

fun PipelineOwner.fileWrite(connectionConfig: FileConnectionConfig, block: FileWriteBuilder.() -> Unit) =
    fileWrite(ConfigRef.ByReference(connectionConfig), block)

fun PipelineOwner.fileList(connectionConfig: ConfigRef, block: FileListBuilder.() -> Unit) {
    processor(FileListBuilder(connectionConfig).apply(block).build())
}

fun PipelineOwner.fileList(connectionConfig: FileConnectionConfig, block: FileListBuilder.() -> Unit) =
    fileList(ConfigRef.ByReference(connectionConfig), block)

fun PipelineOwner.fileCopy(connectionConfig: ConfigRef, block: FileCopyBuilder.() -> Unit) {
    processor(FileCopyBuilder(connectionConfig).apply(block).build())
}

fun PipelineOwner.fileCopy(connectionConfig: FileConnectionConfig, block: FileCopyBuilder.() -> Unit) =
    fileCopy(ConfigRef.ByReference(connectionConfig), block)

fun PipelineOwner.fileMove(connectionConfig: ConfigRef, block: FileMoveBuilder.() -> Unit) {
    processor(FileMoveBuilder(connectionConfig).apply(block).build())
}

fun PipelineOwner.fileMove(connectionConfig: FileConnectionConfig, block: FileMoveBuilder.() -> Unit) =
    fileMove(ConfigRef.ByReference(connectionConfig), block)

fun PipelineOwner.fileDelete(connectionConfig: ConfigRef, block: FileDeleteBuilder.() -> Unit) {
    processor(FileDeleteBuilder(connectionConfig).apply(block).build())
}

fun PipelineOwner.fileDelete(connectionConfig: FileConnectionConfig, block: FileDeleteBuilder.() -> Unit) =
    fileDelete(ConfigRef.ByReference(connectionConfig), block)

fun PipelineOwner.fileRename(connectionConfig: ConfigRef, block: FileRenameBuilder.() -> Unit) {
    processor(FileRenameBuilder(connectionConfig).apply(block).build())
}

fun PipelineOwner.fileRename(connectionConfig: FileConnectionConfig, block: FileRenameBuilder.() -> Unit) =
    fileRename(ConfigRef.ByReference(connectionConfig), block)

fun PipelineOwner.fileCreateDirectory(
    connectionConfig: ConfigRef,
    block: FileCreateDirectoryBuilder.() -> Unit,
) {
    processor(FileCreateDirectoryBuilder(connectionConfig).apply(block).build())
}

fun PipelineOwner.fileCreateDirectory(
    connectionConfig: FileConnectionConfig,
    block: FileCreateDirectoryBuilder.() -> Unit,
) = fileCreateDirectory(ConfigRef.ByReference(connectionConfig), block)
