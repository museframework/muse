package dev.museframework.connectors.sftp.source

import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.source.MuseEventEnvelope
import dev.museframework.engine.api.source.MuseSource
import dev.museframework.engine.api.streaming.StreamingResourceRegistry
import dev.museframework.engine.api.streaming.StreamingProviders
import dev.museframework.connectors.sftp.client.MuseSftpClient
import dev.museframework.connectors.sftp.client.SftpRemoteStreamProvider
import dev.museframework.sftp.api.config.SftpListenerConfig
import dev.museframework.sftp.api.model.SftpFileAttributes
import dev.museframework.sftp.api.model.SftpPostAction
import dev.museframework.sftp.api.model.SftpTransferRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

internal class SftpListenerMuseSource(
    private val config: SftpListenerConfig,
    private val client: MuseSftpClient,
) : MuseSource {
    override val docName: String = config.docName
    override val docId: String = config.docId
    override val docDescription: String? = config.docDescription
    override val sourceFile: String? = config.sourceFile
    override val sourceLine: Int? = config.sourceLine
    override val sourceClass: String? = config.sourceClass

    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val processedVersions = ConcurrentHashMap<String, FileVersion>()

    @Volatile
    private var stopped = false

    override suspend fun start(channel: SendChannel<MuseEventEnvelope>) {
        stopped = false
        while (!stopped && currentCoroutineContext().isActive) {
            try {
                poll(channel)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                logger.error("SFTP listener '{}' failed to poll '{}'", docName, config.directory, failure)
            }
            if (!stopped) delay(config.pollIntervalMs)
        }
    }

    override suspend fun stop() {
        stopped = true
    }

    override suspend fun onResult(
        event: MuseEvent,
        transactionContext: Any?,
    ) {
        val transaction = transactionContext as? ListenerTransaction ?: return
        var completed = event.error == null
        event.error?.let { error ->
            logger.error(
                "SFTP listener '{}' failed to process '{}': {}",
                docName,
                transaction.path,
                error.detailedDescription,
                error.cause,
            )
        }
        try {
            if (completed || config.applyPostActionOnFailure) {
                withContext(Dispatchers.IO) { applyPostAction(transaction.path) }
                completed = true
            }
            if (completed && config.watermarkEnabled) {
                processedVersions[transaction.path] = transaction.version
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            logger.error(
                "SFTP listener '{}' failed to apply post action for '{}'",
                docName,
                transaction.path,
                failure,
            )
        } finally {
            inFlight.remove(transaction.path)
        }
    }

    private suspend fun poll(channel: SendChannel<MuseEventEnvelope>) {
        val files =
            withContext(Dispatchers.IO) {
                client.list(config.directory, config.recursive, config.matcher)
            }
        for (attributes in files) {
            if (stopped || !currentCoroutineContext().isActive) return
            if (attributes.directory || !inFlight.add(attributes.path)) continue

            val stableAttributes =
                try {
                    stableAttributes(attributes)
                } catch (failure: CancellationException) {
                    inFlight.remove(attributes.path)
                    throw failure
                } catch (failure: Throwable) {
                    inFlight.remove(attributes.path)
                    logger.debug("SFTP listener skipped unavailable file '{}'", attributes.path, failure)
                    continue
                }
            val version = stableAttributes.version()
            if (config.watermarkEnabled && processedVersions[stableAttributes.path] == version) {
                inFlight.remove(stableAttributes.path)
                continue
            }

            val provider = StreamingProviders.managed(
                SftpRemoteStreamProvider(
                    client,
                    stableAttributes.path,
                    stableAttributes.size,
                    nonRepeatable = config.nonRepeatable,
                ),
            )
            val envelope =
                MuseEventEnvelope(
                    event =
                        MuseEvent(
                            payload = TypedValue.of(
                                provider as Any,
                                config.outputMimeType ?: TypedValue.MIME_OCTET_STREAM,
                            ),
                            attributes = TypedValue.of(stableAttributes),
                        ),
                    transactionContext = ListenerTransaction(stableAttributes.path, version),
                )
            envelope.replyTo.invokeOnCompletion { failure ->
                if (failure != null) {
                    inFlight.remove(stableAttributes.path)
                    if (failure !is CancellationException) {
                        logger.error("SFTP listener '{}' execution failed for '{}'", docName, stableAttributes.path, failure)
                    }
                }
            }
            if (channel.trySend(envelope).isFailure) {
                inFlight.remove(stableAttributes.path)
                // A failed send never became an engine-owned request: the sender still owns the claims.
                releaseQuietly(envelope.executionResources)
            }
        }
    }

    /** Cleanup failures must not stop the listener from polling later files. */
    private fun releaseQuietly(registry: StreamingResourceRegistry) {
        try {
            registry.releaseAll()
        } catch (failure: Throwable) {
            logger.error("SFTP listener '{}' failed to release an undelivered event's resources", docName, failure)
        }
    }

    private suspend fun stableAttributes(attributes: SftpFileAttributes): SftpFileAttributes {
        val delayMs = config.sizeCheckDelayMs ?: return attributes
        delay(delayMs)
        val current = withContext(Dispatchers.IO) { client.stat(attributes.path) }
        check(current.size == attributes.size && current.modifiedAt == attributes.modifiedAt) {
            "SFTP file '${attributes.path}' is still changing"
        }
        return current
    }

    private fun applyPostAction(path: String) {
        when (val action = config.postAction) {
            SftpPostAction.None -> Unit
            SftpPostAction.Delete -> client.delete(path)
            is SftpPostAction.Move ->
                client.move(
                    SftpTransferRequest(
                        sourcePath = path,
                        targetDirectory = action.targetDirectory,
                        createParentDirectories = true,
                        overwrite = action.overwrite,
                        renameTo = null,
                    ),
                )
        }
    }

    private fun SftpFileAttributes.version(): FileVersion = FileVersion(size, modifiedAt)

    private data class FileVersion(
        val size: Long,
        val modifiedAt: Instant?,
    )

    private data class ListenerTransaction(
        val path: String,
        val version: FileVersion,
    )

    private companion object {
        val logger = LoggerFactory.getLogger(SftpListenerMuseSource::class.java)
    }
}
