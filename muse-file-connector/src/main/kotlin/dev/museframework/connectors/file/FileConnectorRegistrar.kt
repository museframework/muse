package dev.museframework.connectors.file

import dev.museframework.connectors.file.internal.LocalFileSystem
import dev.museframework.connectors.file.processor.FileOperationMuseProcessor
import dev.museframework.connectors.file.source.FileListenerMuseSource
import dev.museframework.dsl.api.model.component.Processor
import dev.museframework.dsl.api.model.config.ConfigRef
import dev.museframework.engine.api.spi.MuseComponentFactory
import dev.museframework.engine.api.spi.MuseComponentRegistrar
import dev.museframework.engine.api.spi.MuseContext
import dev.museframework.engine.api.spi.ProcessorBuilder
import dev.museframework.file.api.config.*

class FileConnectorRegistrar : MuseComponentRegistrar {
    override fun register(factory: MuseComponentFactory) {
        factory.registerConfig(FileConnectionConfig::class) { config, context ->
            context.properties[fileSystemPropertyKey(config)] = LocalFileSystem(resolveWorkingDirectory(config, context))
        }
        factory.registerSource(FileListenerConfig::class) { component, context, evaluator ->
            val connection = resolveConnection(component.connectionConfig, context)
            FileListenerMuseSource(
                config = component,
                connectionConfig = connection,
                expressionEvaluator = evaluator,
                fileSystem = resolveFileSystem(connection, context),
            )
        }
        factory.registerProcessor(FileReadConfig::class, fileOperationBuilder())
        factory.registerProcessor(FileWriteConfig::class, fileOperationBuilder())
        factory.registerProcessor(FileListConfig::class, fileOperationBuilder())
        factory.registerProcessor(FileCopyConfig::class, fileOperationBuilder())
        factory.registerProcessor(FileMoveConfig::class, fileOperationBuilder())
        factory.registerProcessor(FileDeleteConfig::class, fileOperationBuilder())
        factory.registerProcessor(FileRenameConfig::class, fileOperationBuilder())
        factory.registerProcessor(FileCreateDirectoryConfig::class, fileOperationBuilder())
    }

    private fun <T : Processor> fileOperationBuilder(): ProcessorBuilder<T> =
        ProcessorBuilder { component, context ->
            val connection = resolveConnection(component.connectionConfig(), context)
            FileOperationMuseProcessor(
                component = component,
                connectionConfig = connection,
                expressionEvaluator = context.expressionEvaluator,
                fileSystem = resolveFileSystem(connection, context),
            )
        }

    private fun Processor.connectionConfig(): ConfigRef =
        when (this) {
            is FileReadConfig -> connectionConfig
            is FileWriteConfig -> connectionConfig
            is FileListConfig -> connectionConfig
            is FileCopyConfig -> connectionConfig
            is FileMoveConfig -> connectionConfig
            is FileDeleteConfig -> connectionConfig
            is FileRenameConfig -> connectionConfig
            is FileCreateDirectoryConfig -> connectionConfig
            else -> error("Unsupported file operation ${this::class.qualifiedName}")
        }

    private fun resolveConnection(ref: ConfigRef, context: MuseContext): FileConnectionConfig =
        when (ref) {
            is ConfigRef.ByReference ->
                ref.config as? FileConnectionConfig
                    ?: error("File operation references ${ref.config::class.qualifiedName}, expected FileConnectionConfig")
            is ConfigRef.ByName ->
                (context.configs[ref.name] as? FileConnectionConfig)
                    ?: context.configs.values
                        .filterIsInstance<FileConnectionConfig>()
                        .singleOrNull { it.name == ref.name }
                    ?: error("File connection config '${ref.name}' not found")
        }

    companion object {
        private val propertyPlaceholder = Regex("""\$\{([^}]+)}""")

        internal fun fileSystemPropertyKey(config: FileConnectionConfig): String =
            "file.filesystem.${config.name.ifBlank { config.docId }}"

        private fun resolveWorkingDirectory(config: FileConnectionConfig, context: MuseContext): FileConnectionConfig =
            config.copy(
                workingDirectory =
                    config.workingDirectory?.let { directory ->
                        propertyPlaceholder.replace(directory) { match ->
                            context.properties.getProperty(match.groupValues[1]) ?: match.value
                        }
                    },
            )

        internal fun resolveFileSystem(config: FileConnectionConfig, context: MuseContext): LocalFileSystem {
            val key = fileSystemPropertyKey(config)
            val existing = context.properties[key] as? LocalFileSystem
            if (existing != null) return existing
            return LocalFileSystem(resolveWorkingDirectory(config, context)).also { context.properties[key] = it }
        }
    }
}
