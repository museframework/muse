package dev.museframework.connectors.sftp

import dev.museframework.connectors.sftp.client.ApacheMinaSftpClientFactory
import dev.museframework.connectors.sftp.client.MuseSftpClientFactory
import dev.museframework.connectors.sftp.processor.SftpOperationMuseProcessor
import dev.museframework.connectors.sftp.source.SftpListenerMuseSource
import dev.museframework.engine.api.spi.MuseComponentFactory
import dev.museframework.engine.api.spi.MuseComponentRegistrar
import dev.museframework.engine.api.spi.MuseContext
import dev.museframework.engine.api.spi.ProcessorBuilder
import dev.museframework.engine.api.spi.SourceBuilder
import dev.museframework.sftp.api.config.SftpConnectionConfig
import dev.museframework.sftp.api.config.SftpConnectionRef
import dev.museframework.sftp.api.config.SftpCopyConfig
import dev.museframework.sftp.api.config.SftpCreateDirectoryConfig
import dev.museframework.sftp.api.config.SftpDeleteConfig
import dev.museframework.sftp.api.config.SftpListConfig
import dev.museframework.sftp.api.config.SftpListenerConfig
import dev.museframework.sftp.api.config.SftpMoveConfig
import dev.museframework.sftp.api.config.SftpOperationConfig
import dev.museframework.sftp.api.config.SftpReadConfig
import dev.museframework.sftp.api.config.SftpRenameConfig
import dev.museframework.sftp.api.config.SftpWriteConfig
class SftpConnectorRegistrar : MuseComponentRegistrar {
    private val clientFactory: MuseSftpClientFactory = ApacheMinaSftpClientFactory()

    override fun register(factory: MuseComponentFactory) {
        factory.registerConfig(SftpConnectionConfig::class) { config, context ->
            if (config.name.isNotBlank()) {
                context.properties[connectionProperty(config.name)] = config
            }
            if (config.docName.isNotBlank()) {
                context.properties[connectionProperty(config.docName)] = config
            }
        }
        factory.registerProcessor(SftpListConfig::class, processorBuilder())
        factory.registerProcessor(SftpReadConfig::class, processorBuilder())
        factory.registerProcessor(SftpWriteConfig::class, processorBuilder())
        factory.registerProcessor(SftpCopyConfig::class, processorBuilder())
        factory.registerProcessor(SftpMoveConfig::class, processorBuilder())
        factory.registerProcessor(SftpDeleteConfig::class, processorBuilder())
        factory.registerProcessor(SftpRenameConfig::class, processorBuilder())
        factory.registerProcessor(SftpCreateDirectoryConfig::class, processorBuilder())
        factory.registerSource(SftpListenerConfig::class, sourceBuilder())
    }

    private fun <T : SftpOperationConfig> processorBuilder(): ProcessorBuilder<T> =
        ProcessorBuilder { component, context ->
            val connection = resolveSftpConnectionProperties(resolveConnection(component.connection, context), context.properties)
            SftpOperationMuseProcessor(
                component = component,
                client = clientFactory.create(connection),
                expressionEvaluator = context.expressionEvaluator,
            )
        }

    private fun sourceBuilder(): SourceBuilder<SftpListenerConfig> =
        SourceBuilder { component, context, _ ->
            val connection = resolveSftpConnectionProperties(resolveConnection(component.connection, context), context.properties)
            SftpListenerMuseSource(component, clientFactory.create(connection))
        }
}

internal fun resolveConnection(
    reference: SftpConnectionRef,
    context: MuseContext,
): SftpConnectionConfig =
    when (reference) {
        is SftpConnectionRef.ByReference -> reference.config
        is SftpConnectionRef.ByName ->
            context.properties[connectionProperty(reference.name)] as? SftpConnectionConfig
                ?: context.configs.values
                    .filterIsInstance<SftpConnectionConfig>()
                    .firstOrNull { it.name == reference.name || it.docName == reference.name }
                ?: throw IllegalStateException("SFTP connection config '${reference.name}' not found")
    }

private fun connectionProperty(name: String): String = "sftp.connection.$name"
