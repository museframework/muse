package dev.museframework.babelfish.mappers.file

import dev.museframework.babelfish.api.SourceMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.SourceCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.model.config.ConfigRef
import dev.museframework.file.api.config.FileConnectionConfig
import dev.museframework.file.api.config.FileListenerConfig
import dev.museframework.file.api.model.WatermarkMode
import java.util.concurrent.TimeUnit

object FileListenerMapper : SourceMapper<FileListenerConfig> {
    override fun map(
        element: RawElement,
        ctx: MappingContext,
    ): FileListenerConfig =
        FileListenerConfig(
            docName = element.attrs["doc:name"].orEmpty(),
            connectionConfig = ConfigRef.ByName(element.requireAttr("config-ref", element.location)),
            directory = element.attrs["directory"] ?: ".",
            recursive = element.attrs["recursive"]?.toBooleanStrictOrNull() ?: true,
            matcher = resolveFileMatcher(element, ctx),
            watermarkMode =
                element.attrs["watermarkMode"]?.toFileEnum<WatermarkMode>()
                    ?: WatermarkMode.DISABLED,
            pollingFrequencyMillis = pollingFrequencyMillis(element),
            startDelayMillis = startDelayMillis(element),
            timeBetweenSizeCheck = element.attrs["timeBetweenSizeCheck"]?.toLongOrNull(),
            timeBetweenSizeCheckUnit = element.attrs["timeBetweenSizeCheckUnit"]?.toTimeUnit(),
            outputMimeType = element.attrs["outputMimeType"],
            encoding = element.attrs["outputEncoding"] ?: element.attrs["encoding"],
            autoDelete = element.attrs["autoDelete"]?.toBooleanStrictOrNull() ?: false,
            moveToDirectory = element.attrs["moveToDirectory"],
            renameTo = element.attrs["renameTo"],
            applyPostActionWhenFailed =
                element.attrs["applyPostActionWhenFailed"]?.toBooleanStrictOrNull() ?: true,
            overwrite = element.attrs["overwrite"]?.toBooleanStrictOrNull() ?: false,
        )
}

object FileListenerCodeEmitter : SourceCodeEmitter<FileListenerConfig> {
    override fun emit(
        source: FileListenerConfig,
        ctx: CodegenContext,
    ): String {
        ctx.addImport("dev.museframework.file.api.dsl.fileConnection")
        ctx.addImport("dev.museframework.file.api.dsl.fileListener")
        ctx.addImport("dev.museframework.file.api.model.WatermarkMode")
        ctx.addImport("java.util.concurrent.TimeUnit")

        val configArg =
            when (val ref = source.connectionConfig) {
                is ConfigRef.ByName -> "\"${ctx.escapeKotlinString(ref.name)}\""
                is ConfigRef.ByReference -> {
                    val config = ref.config as? FileConnectionConfig
                    val name = config?.name?.ifBlank { config.docName } ?: config?.docName.orEmpty()
                    "\"${ctx.escapeKotlinString(name)}\""
                }
            }

        return buildString {
            appendLine("fileListener($configArg) {")
            if (source.directory != ".") {
                appendLine("    directory = \"${ctx.escapeKotlinString(source.directory)}\"")
            }
            appendNonDefaultBoolean("recursive", source.recursive, default = true)
            appendLocalFileMatcherAssignment("matcher", source.matcher, ctx)
            if (source.watermarkMode != WatermarkMode.DISABLED) {
                appendLine("    watermarkMode = WatermarkMode.${source.watermarkMode}")
            }
            if (source.pollingFrequencyMillis != 1_000L) {
                appendLine("    pollingFrequencyMillis = ${source.pollingFrequencyMillis}")
            }
            if (source.startDelayMillis != 0L) {
                appendLine("    startDelayMillis = ${source.startDelayMillis}")
            }
            appendOptionalLong("timeBetweenSizeCheck", source.timeBetweenSizeCheck)
            appendTimeUnit("timeBetweenSizeCheckUnit", source.timeBetweenSizeCheckUnit)
            appendOptionalString("outputMimeType", source.outputMimeType, ctx)
            appendOptionalString("encoding", source.encoding, ctx)
            appendNonDefaultBoolean("autoDelete", source.autoDelete, default = false)
            appendOptionalString("moveToDirectory", source.moveToDirectory, ctx)
            appendOptionalString("renameTo", source.renameTo, ctx)
            appendNonDefaultBoolean(
                "applyPostActionWhenFailed",
                source.applyPostActionWhenFailed,
                default = true,
            )
            appendNonDefaultBoolean("overwrite", source.overwrite, default = false)
            append("}")
        }
    }
}
