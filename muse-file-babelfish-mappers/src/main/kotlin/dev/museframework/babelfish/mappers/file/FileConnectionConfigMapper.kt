package dev.museframework.babelfish.mappers.file

import dev.museframework.babelfish.api.ConfigMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ConfigCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawConfig
import dev.museframework.file.api.config.FileConnectionConfig
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
    ): String {
        ctx.addImport("dev.museframework.file.api.dsl.fileConnection")
        ctx.addImport("java.util.concurrent.TimeUnit")
        return buildString {
            appendLine("fileConnection {")
            appendLine("    name = \"${ctx.escapeKotlinString(config.name)}\"")
            appendLine("    docName = \"${ctx.escapeKotlinString(config.docName)}\"")
            appendOptionalString("workingDirectory", config.workingDirectory, ctx)
            appendOptionalLong("timeBetweenSizeCheck", config.timeBetweenSizeCheck)
            appendTimeUnit(
                "timeBetweenSizeCheckUnit",
                config.timeBetweenSizeCheckUnit,
                default = TimeUnit.MILLISECONDS,
            )
            append("}")
        }
    }
}
