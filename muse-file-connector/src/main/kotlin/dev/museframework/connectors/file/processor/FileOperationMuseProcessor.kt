package dev.museframework.connectors.file.processor

import dev.museframework.connectors.file.internal.FileConnectorException
import dev.museframework.connectors.file.internal.LocalFileSystem
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.model.component.MuseComponent
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.MuseProcessor
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.execution.policy.PolicyOutcome
import dev.museframework.engine.api.execution.projectTarget
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.file.api.config.*
import dev.museframework.file.api.model.FileErrorType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.Charset

class FileOperationMuseProcessor internal constructor(
    override val component: MuseComponent,
    private val connectionConfig: FileConnectionConfig,
    private val expressionEvaluator: MuseExpressionEvaluator,
    private val fileSystem: LocalFileSystem = LocalFileSystem(connectionConfig),
) : MuseProcessor {
    override suspend fun process(context: ExecutionContext): ProcessorResult =
        try {
            withContext(Dispatchers.IO) {
                when (val operation = component) {
                    is FileReadConfig -> read(operation, context)
                    is FileWriteConfig -> write(operation, context)
                    is FileListConfig -> list(operation, context)
                    is FileCopyConfig -> copy(operation, context)
                    is FileMoveConfig -> move(operation, context)
                    is FileDeleteConfig -> delete(operation, context)
                    is FileRenameConfig -> rename(operation, context)
                    is FileCreateDirectoryConfig -> createDirectory(operation, context)
                    else -> error("Unsupported file operation ${operation::class.qualifiedName}")
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failure(context, failure)
        }

    private fun read(operation: FileReadConfig, context: ExecutionContext): ProcessorResult {
        val path = evaluate(operation.path, context)
        val result =
            fileSystem.read(
                pathExpression = path,
                lock = operation.lock,
                timeBetweenSizeCheck = operation.timeBetweenSizeCheck ?: connectionConfig.timeBetweenSizeCheck,
                timeBetweenSizeCheckUnit =
                    operation.timeBetweenSizeCheckUnit ?: connectionConfig.timeBetweenSizeCheckUnit,
                outputMimeType = operation.outputMimeType,
            )
        context.streamingResources.register(result.provider)
        val charset = operation.encoding?.let(Charset::forName) ?: Charsets.UTF_8
        val event =
            context.event.copy(
                payload = TypedValue.of(result.provider as Any, result.mimeType, charset),
                attributes = TypedValue.of(result.attributes as Any),
            )
        return success(context, context.withEvent(event), operation.targetConfig)
    }

    private fun write(operation: FileWriteConfig, context: ExecutionContext): ProcessorResult {
        val path = evaluate(operation.path, context)
        val contentValue =
            operation.content?.let { expressionEvaluator.evaluate(it, context.event) }
                ?: context.event.payload
        val content =
            contentValue?.getOrNull()
                ?: return failure(
                    context,
                    FileConnectorException(FileErrorType.ILLEGAL_CONTENT, "File write content is null"),
                )
        fileSystem.write(
            pathExpression = path,
            content = content,
            createParentDirectories = operation.createParentDirectories,
            lock = operation.lock,
            mode = operation.mode,
            charset = contentValue.charset,
        )
        return ProcessorResult.Success(context)
    }

    private fun list(operation: FileListConfig, context: ExecutionContext): ProcessorResult {
        val messages =
            fileSystem.list(
                directoryExpression = evaluate(operation.directoryPath, context),
                recursive = operation.recursive,
                matcher = operation.matcher,
                timeBetweenSizeCheck = operation.timeBetweenSizeCheck ?: connectionConfig.timeBetweenSizeCheck,
                timeBetweenSizeCheckUnit =
                    operation.timeBetweenSizeCheckUnit ?: connectionConfig.timeBetweenSizeCheckUnit,
                subset = operation.subset,
            )
        messages.mapNotNull { it.payload }.forEach(context.streamingResources::register)
        val event = context.event.copy(payload = TypedValue.of(messages as Any), attributes = null)
        return success(context, context.withEvent(event), operation.targetConfig)
    }

    private fun copy(operation: FileCopyConfig, context: ExecutionContext): ProcessorResult {
        fileSystem.copy(
            sourceExpression = evaluate(operation.sourcePath, context),
            targetExpression = evaluate(operation.targetPath, context),
            createParentDirectories = operation.createParentDirectories,
            overwrite = operation.overwrite,
            renameTo = operation.renameTo?.let { evaluate(it, context) },
        )
        return ProcessorResult.Success(context)
    }

    private fun move(operation: FileMoveConfig, context: ExecutionContext): ProcessorResult {
        fileSystem.move(
            sourceExpression = evaluate(operation.sourcePath, context),
            targetExpression = evaluate(operation.targetPath, context),
            createParentDirectories = operation.createParentDirectories,
            overwrite = operation.overwrite,
            renameTo = operation.renameTo?.let { evaluate(it, context) },
        )
        return ProcessorResult.Success(context)
    }

    private fun delete(operation: FileDeleteConfig, context: ExecutionContext): ProcessorResult {
        fileSystem.delete(evaluate(operation.path, context))
        return ProcessorResult.Success(context)
    }

    private fun rename(operation: FileRenameConfig, context: ExecutionContext): ProcessorResult {
        fileSystem.rename(
            pathExpression = evaluate(operation.path, context),
            newName = evaluate(operation.to, context),
            overwrite = operation.overwrite,
        )
        return ProcessorResult.Success(context)
    }

    private fun createDirectory(operation: FileCreateDirectoryConfig, context: ExecutionContext): ProcessorResult {
        fileSystem.createDirectory(evaluate(operation.directoryPath, context))
        return ProcessorResult.Success(context)
    }

    private fun evaluate(expression: String, context: ExecutionContext): String =
        expressionEvaluator.evaluate(expression, context.event)?.getOrNull()?.toString() ?: expression

    private fun success(
        base: ExecutionContext,
        result: ExecutionContext,
        targetConfig: TargetConfig?,
    ): ProcessorResult {
        if (targetConfig == null) return ProcessorResult.Success(result)
        val projected = result.projectTarget(PolicyOutcome.Success(result.event), targetConfig, base.event)
        return when (projected) {
            is PolicyOutcome.Success -> ProcessorResult.Success(result.withEvent(projected.event))
            is PolicyOutcome.Failure ->
                ProcessorResult.Failure(
                    projected.error,
                    result.withEvent(projected.event.copy(error = projected.error)),
                )
        }
    }

    private fun failure(context: ExecutionContext, cause: Throwable): ProcessorResult.Failure {
        val connectorFailure = cause as? FileConnectorException
        val error =
            MuseError(
                description = "File operation failed",
                detailedDescription = cause.message ?: cause::class.simpleName.orEmpty(),
                errorType = connectorFailure?.errorType ?: mapErrorType(cause),
                cause = cause,
                sourceFile = component.sourceFile,
                sourceLine = component.sourceLine,
                sourceClass = component.sourceClass,
            )
        return ProcessorResult.Failure(error, context.withError(error))
    }

    private fun mapErrorType(cause: Throwable) =
        when (cause) {
            is java.nio.file.NoSuchFileException -> FileErrorType.ILLEGAL_PATH
            is java.nio.file.FileAlreadyExistsException -> FileErrorType.FILE_ALREADY_EXISTS
            is java.nio.file.AccessDeniedException,
            is SecurityException,
            -> FileErrorType.ACCESS_DENIED
            is java.nio.file.InvalidPathException,
            is java.nio.file.NotDirectoryException,
            -> FileErrorType.ILLEGAL_PATH
            is java.nio.channels.OverlappingFileLockException -> FileErrorType.FILE_LOCK
            is java.nio.charset.UnsupportedCharsetException -> FileErrorType.ILLEGAL_CONTENT
            else -> FileErrorType.ANY
        }
}
