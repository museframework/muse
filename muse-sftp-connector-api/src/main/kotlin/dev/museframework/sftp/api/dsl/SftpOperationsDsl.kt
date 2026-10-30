package dev.museframework.sftp.api.dsl

import dev.museframework.dsl.api.dsl.PipelineOwner
import dev.museframework.sftp.api.config.SftpConnectionConfig
import dev.museframework.sftp.api.config.SftpConnectionRef

fun PipelineOwner.sftpList(
    connection: SftpConnectionRef,
    block: SftpListBuilder.() -> Unit = {},
) {
    processor(SftpListBuilder(connection).apply(block).build())
}

fun PipelineOwner.sftpList(
    connection: SftpConnectionConfig,
    block: SftpListBuilder.() -> Unit = {},
) = sftpList(SftpConnectionRef.ByReference(connection), block)

fun PipelineOwner.sftpRead(
    connection: SftpConnectionRef,
    block: SftpReadBuilder.() -> Unit,
) {
    processor(SftpReadBuilder(connection).apply(block).build())
}

fun PipelineOwner.sftpRead(
    connection: SftpConnectionConfig,
    block: SftpReadBuilder.() -> Unit,
) = sftpRead(SftpConnectionRef.ByReference(connection), block)

fun PipelineOwner.sftpWrite(
    connection: SftpConnectionRef,
    block: SftpWriteBuilder.() -> Unit,
) {
    processor(SftpWriteBuilder(connection).apply(block).build())
}

fun PipelineOwner.sftpWrite(
    connection: SftpConnectionConfig,
    block: SftpWriteBuilder.() -> Unit,
) = sftpWrite(SftpConnectionRef.ByReference(connection), block)

fun PipelineOwner.sftpCopy(
    connection: SftpConnectionRef,
    block: SftpCopyBuilder.() -> Unit,
) {
    processor(SftpCopyBuilder(connection).apply(block).build())
}

fun PipelineOwner.sftpCopy(
    connection: SftpConnectionConfig,
    block: SftpCopyBuilder.() -> Unit,
) = sftpCopy(SftpConnectionRef.ByReference(connection), block)

fun PipelineOwner.sftpMove(
    connection: SftpConnectionRef,
    block: SftpMoveBuilder.() -> Unit,
) {
    processor(SftpMoveBuilder(connection).apply(block).build())
}

fun PipelineOwner.sftpMove(
    connection: SftpConnectionConfig,
    block: SftpMoveBuilder.() -> Unit,
) = sftpMove(SftpConnectionRef.ByReference(connection), block)

fun PipelineOwner.sftpDelete(
    connection: SftpConnectionRef,
    block: SftpDeleteBuilder.() -> Unit,
) {
    processor(SftpDeleteBuilder(connection).apply(block).build())
}

fun PipelineOwner.sftpDelete(
    connection: SftpConnectionConfig,
    block: SftpDeleteBuilder.() -> Unit,
) = sftpDelete(SftpConnectionRef.ByReference(connection), block)

fun PipelineOwner.sftpRename(
    connection: SftpConnectionRef,
    block: SftpRenameBuilder.() -> Unit,
) {
    processor(SftpRenameBuilder(connection).apply(block).build())
}

fun PipelineOwner.sftpRename(
    connection: SftpConnectionConfig,
    block: SftpRenameBuilder.() -> Unit,
) = sftpRename(SftpConnectionRef.ByReference(connection), block)

fun PipelineOwner.sftpCreateDirectory(
    connection: SftpConnectionRef,
    block: SftpCreateDirectoryBuilder.() -> Unit,
) {
    processor(SftpCreateDirectoryBuilder(connection).apply(block).build())
}

fun PipelineOwner.sftpCreateDirectory(
    connection: SftpConnectionConfig,
    block: SftpCreateDirectoryBuilder.() -> Unit,
) = sftpCreateDirectory(SftpConnectionRef.ByReference(connection), block)
