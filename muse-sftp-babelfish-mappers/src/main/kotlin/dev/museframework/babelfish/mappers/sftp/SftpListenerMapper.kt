package dev.museframework.babelfish.mappers.sftp

import dev.museframework.babelfish.api.SourceMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.SourceCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.sftp.api.config.SftpConnectionRef
import dev.museframework.sftp.api.config.SftpListenerConfig
import dev.museframework.sftp.api.model.SftpFileMatcher
import dev.museframework.sftp.api.model.SftpFileType
import dev.museframework.sftp.api.model.SftpPostAction
import java.util.UUID
import java.util.concurrent.TimeUnit

object SftpListenerMapper : SourceMapper<SftpListenerConfig> {
    override fun map(
        element: RawElement,
        ctx: MappingContext,
    ): SftpListenerConfig {
        val autoDelete = element.attrs["autoDelete"]?.toBooleanStrictOrNull() ?: false
        val moveToDirectory = element.attrs["moveToDirectory"]
        val overwrite = element.attrs["overwrite"]?.toBooleanStrictOrNull() ?: false
        val postAction =
            when {
                autoDelete -> SftpPostAction.Delete
                moveToDirectory != null -> SftpPostAction.Move(moveToDirectory, overwrite)
                else -> SftpPostAction.None
            }
        val watermarkEnabled =
            element.attrs["watermarkEnabled"]?.toBooleanStrictOrNull()
                ?: when (element.attrs["watermarkMode"]?.uppercase()) {
                    null, "", "DISABLED", "NONE", "FALSE" -> false
                    else -> true
                }

        return SftpListenerConfig(
            docName = element.attrs["doc:name"].orEmpty(),
            docId = element.attrs["doc:id"] ?: UUID.randomUUID().toString(),
            connection = SftpConnectionRef.ByName(element.requireAttr("config-ref", element.location)),
            directory = element.attrs["directory"] ?: "/",
            recursive = element.attrs["recursive"]?.toBooleanStrictOrNull() ?: true,
            matcher =
                mapSftpMatcher(
                    element,
                    ctx,
                    defaultFileType = SftpFileType.REGULAR_FILE,
                ),
            pollIntervalMs = pollIntervalMs(element),
            sizeCheckDelayMs =
                durationToMillis(
                    element.attrs["timeBetweenSizeCheck"],
                    element.attrs["timeBetweenSizeCheckUnit"],
                    TimeUnit.MILLISECONDS,
                ),
            watermarkEnabled = watermarkEnabled,
            postAction = postAction,
            applyPostActionOnFailure =
                element.attrs["applyPostActionWhenFailed"]?.toBooleanStrictOrNull() ?: false,
            outputMimeType = element.attrs["outputMimeType"],
            nonRepeatable = element.children.any { it.tag == "non-repeatable-stream" },
        )
    }
}

object SftpListenerCodeEmitter : SourceCodeEmitter<SftpListenerConfig> {
    override fun emit(
        source: SftpListenerConfig,
        ctx: CodegenContext,
    ): String {
        ctx.addImport("dev.museframework.sftp.api.dsl.sftpListener")
        ctx.addImport("dev.museframework.sftp.api.model.SftpPostAction")
        val defaultMatcher = SftpFileMatcher(fileType = SftpFileType.REGULAR_FILE)
        return buildString {
            appendLine("sftpListener(${connectionNameExpression(source.connection, ctx)}) {")
            appendNonDefaultString("docName", source.docName, "", ctx)
            appendOptionalString("docId", source.docId, ctx)
            appendOptionalString("outputMimeType", source.outputMimeType, ctx)
            appendNonDefaultBoolean("nonRepeatable", source.nonRepeatable, default = false)
            appendNonDefaultString("directory", source.directory, "/", ctx)
            appendNonDefaultBoolean("recursive", source.recursive, default = true)
            appendSftpMatcherAssignment("matcher", source.matcher, defaultMatcher, ctx)
            appendNonDefaultLong("pollIntervalMs", source.pollIntervalMs, 1_000L)
            appendOptionalLong("sizeCheckDelayMs", source.sizeCheckDelayMs)
            appendNonDefaultBoolean("watermarkEnabled", source.watermarkEnabled, default = true)
            when (val action = source.postAction) {
                SftpPostAction.None -> Unit
                SftpPostAction.Delete -> appendLine("    deleteAfterProcessing()")
                is SftpPostAction.Move -> {
                    append("    moveAfterProcessing(\"${ctx.escapeKotlinString(action.targetDirectory)}\"")
                    if (action.overwrite) append(", overwrite = true")
                    appendLine(")")
                }
            }
            appendNonDefaultBoolean(
                "applyPostActionOnFailure",
                source.applyPostActionOnFailure,
                default = false,
            )
            append("}")
        }
    }
}
