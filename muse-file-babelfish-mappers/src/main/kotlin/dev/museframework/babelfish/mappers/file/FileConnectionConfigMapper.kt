package dev.museframework.babelfish.mappers.file

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.buildCodeBlock
import dev.museframework.babelfish.api.ConfigMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ConfigCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawConfig
import dev.museframework.file.api.config.FileConnectionConfig
import java.util.UUID
import java.util.concurrent.TimeUnit

object FileConnectionConfigMapper : ConfigMapper {
    override fun map(
        config: RawConfig,
        ctx: MappingContext,
    ): FileConnectionConfig {
        val configName = config.attrs["name"] ?: config.id
        val connection = config.children.find { it.tag == "connection" }
        return FileConnectionConfig(
            name = configName,
            docName = config.attrs["doc:name"] ?: configName,
            docId = config.attrs["doc:id"] ?: UUID.randomUUID().toString(),
            workingDirectory =
                connection?.attrs?.get("workingDir")
                    ?: connection?.attrs?.get("workingDirectory")
                    ?: config.attrs["workingDir"]
                    ?: config.attrs["workingDirectory"],
            timeBetweenSizeCheck =
                connection?.attrs?.get("timeBetweenSizeCheck")?.toLongOrNull()
                    ?: config.attrs["timeBetweenSizeCheck"]?.toLongOrNull(),
            timeBetweenSizeCheckUnit =
                connection?.attrs?.get("timeBetweenSizeCheckUnit")?.toTimeUnit()
                    ?: config.attrs["timeBetweenSizeCheckUnit"]?.toTimeUnit()
                    ?: TimeUnit.MILLISECONDS,
        )
    }
}

object FileConnectionConfigCodeEmitter : ConfigCodeEmitter<FileConnectionConfig> {
    override fun emit(
        config: FileConnectionConfig,
        ctx: CodegenContext,
    ): CodeBlock = buildCodeBlock {
        beginControlFlow("%M", fileDsl("fileConnection"))
        addString("name", config.name)
        addString("docName", config.docName)
        addString("docId", config.docId)
        addString("workingDirectory", config.workingDirectory)
        addLong("timeBetweenSizeCheck", config.timeBetweenSizeCheck)
        addTimeUnit("timeBetweenSizeCheckUnit", config.timeBetweenSizeCheckUnit, TimeUnit.MILLISECONDS)
        endControlFlow()
    }
}
