package dev.museframework.babelfish.mappers.file

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.buildCodeBlock
import dev.museframework.babelfish.api.SourceMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.SourceCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.model.config.ConfigRef
import dev.museframework.file.api.config.FileListenerConfig
import dev.museframework.file.api.model.WatermarkMode

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
    ): CodeBlock = buildCodeBlock {
        beginControlFlow("%M(%L)", fileDsl("fileListener"), connectionName(source.connectionConfig))
        if (source.directory != ".") addString("directory", source.directory)
        addNonDefaultBoolean("recursive", source.recursive, default = true)
        addMatcher(source.matcher)
        if (source.watermarkMode != WatermarkMode.DISABLED) {
            addStatement("watermarkMode = %T.%N", WatermarkMode::class, source.watermarkMode.name)
        }
        if (source.pollingFrequencyMillis != 1_000L) addLong("pollingFrequencyMillis", source.pollingFrequencyMillis)
        if (source.startDelayMillis != 0L) addLong("startDelayMillis", source.startDelayMillis)
        addLong("timeBetweenSizeCheck", source.timeBetweenSizeCheck)
        addTimeUnit("timeBetweenSizeCheckUnit", source.timeBetweenSizeCheckUnit)
        addString("outputMimeType", source.outputMimeType)
        addString("encoding", source.encoding)
        addNonDefaultBoolean("autoDelete", source.autoDelete, default = false)
        addString("moveToDirectory", source.moveToDirectory)
        addString("renameTo", source.renameTo)
        addNonDefaultBoolean("applyPostActionWhenFailed", source.applyPostActionWhenFailed, default = true)
        addNonDefaultBoolean("overwrite", source.overwrite, default = false)
        endControlFlow()
    }
}
