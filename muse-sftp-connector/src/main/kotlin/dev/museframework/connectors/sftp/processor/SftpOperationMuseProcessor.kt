package dev.museframework.connectors.sftp.processor

import dev.museframework.connectors.sftp.client.MuseSftpClient
import dev.museframework.connectors.sftp.client.SftpClientFailure
import dev.museframework.connectors.sftp.client.SftpRemoteStreamProvider
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.MuseProcessor
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.execution.policy.PolicyOutcome
import dev.museframework.engine.api.execution.projectTarget
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.engine.api.streaming.MuseCursorStreamProvider
import dev.museframework.engine.api.streaming.StreamingProviders
import dev.museframework.sftp.api.config.SftpCopyConfig
import dev.museframework.sftp.api.config.SftpCreateDirectoryConfig
import dev.museframework.sftp.api.config.SftpDeleteConfig
import dev.museframework.sftp.api.config.SftpListConfig
import dev.museframework.sftp.api.config.SftpMoveConfig
import dev.museframework.sftp.api.config.SftpOperationConfig
import dev.museframework.sftp.api.config.SftpReadConfig
import dev.museframework.sftp.api.config.SftpRenameConfig
import dev.museframework.sftp.api.config.SftpWriteConfig
import dev.museframework.sftp.api.model.SftpErrorType
import dev.museframework.sftp.api.model.SftpTransferRequest
import dev.museframework.sftp.api.model.SftpWriteMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Locale

internal class SftpOperationMuseProcessor(
    override val component: SftpOperationConfig,
    private val client: MuseSftpClient,
    private val expressionEvaluator: MuseExpressionEvaluator,
) : MuseProcessor {
    override suspend fun process(context: ExecutionContext): ProcessorResult =
        try {
            when (val operation = component) {
                is SftpListConfig -> processList(operation, context)
                is SftpReadConfig -> processRead(operation, context)
                is SftpWriteConfig -> processWrite(operation, context)
                is SftpCopyConfig -> processCopy(operation, context)
                is SftpMoveConfig -> processMove(operation, context)
                is SftpDeleteConfig -> processDelete(operation, context)
                is SftpRenameConfig -> processRename(operation, context)
                is SftpCreateDirectoryConfig -> processCreateDirectory(operation, context)
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: SftpClientFailure) {
            failure(failure, context)
        } catch (failure: Throwable) {
            failure(
                SftpClientFailure(
                    SftpErrorType.OPERATION_FAILED,
                    "SFTP operation failed: ${failure.message ?: failure::class.simpleName}",
                    failure,
                ),
                context,
            )
        }

    private suspend fun processList(
        operation: SftpListConfig,
        context: ExecutionContext,
    ): ProcessorResult {
        val path = evaluateString(operation.directoryPath, context.event)
        val files = withContext(Dispatchers.IO) { client.list(path, operation.recursive, operation.matcher) }
        val event = context.event.copy(payload = TypedValue.of(files), attributes = null)
        return output(context, event, operation.targetConfig)
    }

    private suspend fun processRead(
        operation: SftpReadConfig,
        context: ExecutionContext,
    ): ProcessorResult {
        val path = evaluateString(operation.path, context.event)
        val attributes = withContext(Dispatchers.IO) { client.stat(path) }
        if (attributes.directory) {
            throw SftpClientFailure(
                SftpErrorType.IS_A_DIRECTORY,
                "SFTP path '$path' is a directory and cannot be read as a file",
            )
        }
        val provider = StreamingProviders.managed(SftpRemoteStreamProvider(client, path, attributes.size))
        context.streamingResources.register(provider)
        val event =
            context.event.copy(
                payload = TypedValue.of(provider as Any, TypedValue.MIME_OCTET_STREAM),
                attributes = TypedValue.of(attributes),
            )
        return output(context, event, operation.targetConfig)
    }

    private suspend fun processWrite(
        operation: SftpWriteConfig,
        context: ExecutionContext,
    ): ProcessorResult {
        val mode = resolveWriteMode(operation, context.event)
        val path = evaluateString(operation.path, context.event)
        val contentExpression = operation.content
        val content =
            if (contentExpression == null) {
                context.event.payload?.getOrNull()
            } else {
                expressionEvaluator.evaluate(contentExpression, context.event)?.getOrNull()
            } ?: throw SftpClientFailure(
                SftpErrorType.OPERATION_FAILED,
                "SFTP write content must not be null",
            )

        withContext(Dispatchers.IO) {
            content.asInputStream(context).use { input ->
                client.write(path, input, mode, operation.createParentDirectories)
            }
        }
        return ProcessorResult.Success(context)
    }

    private fun resolveWriteMode(operation: SftpWriteConfig, event: MuseEvent): SftpWriteMode {
        val expression = operation.modeExpression ?: return operation.mode
        val value = expressionEvaluator.evaluate(expression, event)?.getOrNull()
        return try {
            when (value) {
                is SftpWriteMode -> value
                is String -> SftpWriteMode.valueOf(value.trim().uppercase(Locale.ROOT))
                else -> throw IllegalArgumentException("SFTP mode expression must return a string or SftpWriteMode")
            }
        } catch (failure: IllegalArgumentException) {
            throw SftpClientFailure(
                SftpErrorType.OPERATION_FAILED,
                "SFTP mode expression must resolve to OVERWRITE, APPEND or CREATE_NEW",
                failure,
            )
        }
    }

    private suspend fun processCopy(
        operation: SftpCopyConfig,
        context: ExecutionContext,
    ): ProcessorResult {
        withContext(Dispatchers.IO) { client.copy(evaluatedTransfer(operation.transfer, context.event)) }
        return ProcessorResult.Success(context)
    }

    private suspend fun processMove(
        operation: SftpMoveConfig,
        context: ExecutionContext,
    ): ProcessorResult {
        withContext(Dispatchers.IO) { client.move(evaluatedTransfer(operation.transfer, context.event)) }
        return ProcessorResult.Success(context)
    }

    private suspend fun processDelete(
        operation: SftpDeleteConfig,
        context: ExecutionContext,
    ): ProcessorResult {
        val path = evaluateString(operation.path, context.event)
        withContext(Dispatchers.IO) { client.delete(path) }
        return ProcessorResult.Success(context)
    }

    private suspend fun processRename(
        operation: SftpRenameConfig,
        context: ExecutionContext,
    ): ProcessorResult {
        val path = evaluateString(operation.path, context.event)
        val newName = evaluateString(operation.newName, context.event)
        withContext(Dispatchers.IO) { client.rename(path, newName, operation.overwrite) }
        return ProcessorResult.Success(context)
    }

    private suspend fun processCreateDirectory(
        operation: SftpCreateDirectoryConfig,
        context: ExecutionContext,
    ): ProcessorResult {
        val path = evaluateString(operation.directoryPath, context.event)
        withContext(Dispatchers.IO) { client.createDirectory(path) }
        return ProcessorResult.Success(context)
    }

    private fun output(
        context: ExecutionContext,
        event: MuseEvent,
        targetConfig: TargetConfig?,
    ): ProcessorResult {
        if (targetConfig == null) return ProcessorResult.Success(context.withEvent(event))

        return when (
            val projected = context.projectTarget(PolicyOutcome.Success(event), targetConfig, context.event)
        ) {
            is PolicyOutcome.Success -> ProcessorResult.Success(context.withEvent(projected.event))
            is PolicyOutcome.Failure ->
                ProcessorResult.Failure(
                    projected.error,
                    context.withEvent(projected.event.copy(error = projected.error)),
                )
        }
    }

    private fun evaluatedTransfer(
        transfer: SftpTransferRequest,
        event: MuseEvent,
    ): SftpTransferRequest =
        SftpTransferRequest(
            sourcePath = evaluateString(transfer.sourcePath, event),
            targetDirectory = evaluateString(transfer.targetDirectory, event),
            createParentDirectories = transfer.createParentDirectories,
            overwrite = transfer.overwrite,
            renameTo = transfer.renameTo?.let { evaluateString(it, event) },
        )

    private fun evaluateString(
        expression: String,
        event: MuseEvent,
    ): String = expressionEvaluator.evaluate(expression, event)?.getOrNull()?.toString() ?: expression

    private fun Any.asInputStream(context: ExecutionContext): InputStream =
        when (this) {
            is MuseCursorStreamProvider -> {
                context.streamingResources.register(this)
                openCursor()
            }
            is InputStream -> this
            is ByteArray -> ByteArrayInputStream(this)
            is String -> ByteArrayInputStream(toByteArray(Charsets.UTF_8))
            else ->
                throw SftpClientFailure(
                    SftpErrorType.ILLEGAL_CONTENT,
                    "SFTP write content must be a byte array, string, or stream but was ${this::class.simpleName}",
                )
        }

    private fun failure(
        failure: SftpClientFailure,
        context: ExecutionContext,
    ): ProcessorResult.Failure {
        val error =
            MuseError(
                description = failure.message ?: "SFTP operation failed",
                detailedDescription = failure.cause?.message ?: failure.message.orEmpty(),
                errorType = failure.errorType,
                cause = failure,
                sourceFile = component.sourceFile,
                sourceLine = component.sourceLine,
                sourceClass = component.sourceClass,
            )
        return ProcessorResult.Failure(error, context.withError(error))
    }
}
