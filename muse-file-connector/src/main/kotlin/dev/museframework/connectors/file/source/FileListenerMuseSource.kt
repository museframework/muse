package dev.museframework.connectors.file.source

import dev.museframework.connectors.file.internal.LocalFileSystem
import dev.museframework.connectors.file.internal.awaitStableSize
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.engine.api.source.MuseEventEnvelope
import dev.museframework.engine.api.source.MuseSource
import dev.museframework.engine.api.streaming.MuseCursorStreamProvider
import dev.museframework.file.api.config.FileConnectionConfig
import dev.museframework.file.api.config.FileListenerConfig
import dev.museframework.file.api.model.LocalFileAttributes
import dev.museframework.file.api.model.WatermarkMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.charset.Charset
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

class FileListenerMuseSource internal constructor(
    private val config: FileListenerConfig,
    connectionConfig: FileConnectionConfig,
    private val expressionEvaluator: MuseExpressionEvaluator,
    private val fileSystem: LocalFileSystem = LocalFileSystem(connectionConfig),
    private val listRegularFiles: ((String) -> List<LocalFileAttributes>)? = null,
) : MuseSource {
    override val docName: String = config.docName
    override val docId: String = config.docId
    override val docDescription: String? = config.docDescription
    override val sourceFile: String? = config.sourceFile
    override val sourceLine: Int? = config.sourceLine
    override val sourceClass: String? = config.sourceClass

    private val logger = LoggerFactory.getLogger(FileListenerMuseSource::class.java)
    private val defaultTimeBetweenSizeCheck = connectionConfig.timeBetweenSizeCheck
    private val defaultTimeBetweenSizeCheckUnit = connectionConfig.timeBetweenSizeCheckUnit
    private val stopped = AtomicBoolean(false)
    private val stateLock = Any()
    private val seen = mutableMapOf<String, FileFingerprint>()
    private val inflight = mutableMapOf<String, InflightDispatch>()
    private val retryHold = mutableMapOf<String, Instant>()
    private var watermark: Instant? = null
    private var maxAccepted: Instant? = null

    init {
        require(!config.autoDelete || config.moveToDirectory == null) {
            "autoDelete and moveToDirectory are mutually exclusive"
        }
        require(config.renameTo == null || config.moveToDirectory != null) {
            "renameTo requires moveToDirectory"
        }
        require(config.pollingFrequencyMillis > 0) { "pollingFrequencyMillis must be greater than zero" }
        require(config.startDelayMillis >= 0) { "startDelayMillis must be greater than or equal to zero" }
    }

    override suspend fun start(channel: SendChannel<MuseEventEnvelope>) {
        val directory = evaluate(config.directory, MuseEvent())
        withContext(Dispatchers.IO) { fileSystem.requireDirectory(directory) }
        val charset = config.encoding?.let(Charset::forName) ?: Charsets.UTF_8
        if (config.startDelayMillis > 0) {
            delay(config.startDelayMillis)
        }

        while (currentCoroutineContext().isActive && !stopped.get()) {
            try {
                poll(directory, charset, channel)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.warn("Could not poll file listener directory '{}'", directory, failure)
            }
            delay(config.pollingFrequencyMillis)
        }
    }

    override suspend fun stop() {
        stopped.set(true)
    }

    override suspend fun onResult(event: MuseEvent, transactionContext: Any?) {
        val transaction = transactionContext as? FileListenerTransaction ?: return
        synchronized(stateLock) {
            inflight.remove(transaction.path)
            if (event.error == null) {
                seen[transaction.path] = transaction.fingerprint
                retryHold.remove(transaction.path)
                noteAccepted(transaction.attributes)
            } else {
                holdForRetry(transaction.path, transaction.attributes)
            }
            recomputeWatermark()
        }
        val shouldApply = event.error == null || config.applyPostActionWhenFailed
        val mutatesFile = config.autoDelete || config.moveToDirectory != null
        if (!shouldApply || !mutatesFile) return

        try {
            transaction.provider.releaseResources()
            withContext(Dispatchers.IO) {
                if (config.autoDelete) {
                    fileSystem.delete(transaction.path)
                } else {
                    fileSystem.move(
                        sourceExpression = transaction.path,
                        targetExpression = evaluate(requireNotNull(config.moveToDirectory), event),
                        createParentDirectories = true,
                        overwrite = config.overwrite,
                        renameTo = config.renameTo?.let { evaluate(it, event) },
                    )
                }
            }
        } catch (failure: Exception) {
            logger.error("File listener post-processing failed for '{}'", transaction.path, failure)
        }
    }

    private suspend fun poll(
        directory: String,
        charset: Charset,
        channel: SendChannel<MuseEventEnvelope>,
    ) {
        val files =
            withContext(Dispatchers.IO) {
                listRegularFiles?.invoke(directory)
                    ?: fileSystem.listAttributes(
                        directoryExpression = directory,
                        recursive = config.recursive,
                        matcher = config.matcher,
                        subset = null,
                    ).filter { it.regularFile }
            }
        val baseline =
            synchronized(stateLock) {
                inflight.filterValues { it.provider.isClosed }.keys.toList().forEach { path ->
                    val dispatch = inflight.remove(path) ?: return@forEach
                    holdForRetry(path, dispatch.attributes)
                }
                val present = files.mapTo(mutableSetOf()) { it.path }
                seen.keys.retainAll(present)
                retryHold.keys.retainAll(present)
                recomputeWatermark()
                watermark
            }

        for (attributes in files) {
            val fingerprint = FileFingerprint(attributes.size, attributes.lastModifiedTime, attributes.creationTime)
            val alreadyHandled =
                synchronized(stateLock) {
                    seen[attributes.path] == fingerprint || inflight.containsKey(attributes.path)
                }
            if (alreadyHandled || !passesWatermark(attributes, baseline)) continue

            try {
                val timeBetweenSizeCheck = config.timeBetweenSizeCheck ?: defaultTimeBetweenSizeCheck
                val timeBetweenSizeCheckUnit =
                    config.timeBetweenSizeCheckUnit ?: defaultTimeBetweenSizeCheckUnit
                val result =
                    withContext(Dispatchers.IO) {
                        // Withhold until size is stable so a growing file never enters the flow
                        // (and cannot be auto-deleted after a late openCursor stability failure).
                        awaitStableSize(Path.of(attributes.path), timeBetweenSizeCheck, timeBetweenSizeCheckUnit)
                        fileSystem.read(
                            pathExpression = attributes.path,
                            lock = false,
                            timeBetweenSizeCheck = timeBetweenSizeCheck,
                            timeBetweenSizeCheckUnit = timeBetweenSizeCheckUnit,
                            outputMimeType = config.outputMimeType,
                        )
                    }
                val event =
                    MuseEvent(
                        payload = TypedValue.of(result.provider as Any, result.mimeType, charset),
                        attributes = TypedValue.of(result.attributes as Any),
                    )
                synchronized(stateLock) {
                    inflight[attributes.path] = InflightDispatch(result.provider, attributes)
                }
                try {
                    channel.send(
                        MuseEventEnvelope(
                            event = event,
                            transactionContext =
                                FileListenerTransaction(
                                    path = attributes.path,
                                    provider = result.provider,
                                    fingerprint = fingerprint,
                                    attributes = attributes,
                                ),
                        ),
                    )
                } catch (failure: Exception) {
                    synchronized(stateLock) {
                        inflight.remove(attributes.path)
                        holdForRetry(attributes.path, attributes)
                        recomputeWatermark()
                    }
                    result.provider.releaseResources()
                    throw failure
                }
            } catch (cancelled: CancellationException) {
                synchronized(stateLock) { inflight.remove(attributes.path) }
                throw cancelled
            } catch (failure: Exception) {
                synchronized(stateLock) {
                    inflight.remove(attributes.path)
                    holdForRetry(attributes.path, attributes)
                    recomputeWatermark()
                }
                logger.warn("Could not dispatch file '{}'", attributes.path, failure)
            }
        }
    }

    // Equal timestamps stay eligible. The fingerprint already suppresses the same path,
    // and a different file with that timestamp must still be dispatched. Every file in
    // one poll is compared with the watermark from before that poll. The mark is
    // process-local; Muse has no object-store watermark.
    //
    // A successful newer file must not move the watermark past an older file that is
    // still in flight or waiting to be retried. Callers hold stateLock.
    private fun passesWatermark(attributes: LocalFileAttributes, baseline: Instant?): Boolean {
        val current = baseline ?: return true
        val candidate = watermarkValue(attributes) ?: return true
        return candidate >= current
    }

    private fun noteAccepted(attributes: LocalFileAttributes) {
        val candidate = watermarkValue(attributes) ?: return
        if (maxAccepted == null || candidate > maxAccepted) maxAccepted = candidate
    }

    private fun holdForRetry(path: String, attributes: LocalFileAttributes) {
        val candidate = watermarkValue(attributes) ?: return
        retryHold[path] = candidate
    }

    private fun recomputeWatermark() {
        val outstanding =
            (
                inflight.values.mapNotNull { watermarkValue(it.attributes) } + retryHold.values
            ).minOrNull()
        val accepted = maxAccepted
        val current = watermark
        watermark =
            when {
                outstanding != null && (current == null || outstanding < current) -> outstanding
                outstanding != null -> current
                accepted != null && (current == null || accepted > current) -> accepted
                else -> current
            }
    }

    private fun watermarkValue(attributes: LocalFileAttributes): Instant? =
        when (config.watermarkMode) {
            WatermarkMode.DISABLED -> null
            WatermarkMode.MODIFIED_TIMESTAMP -> attributes.lastModifiedTime
            WatermarkMode.CREATED_TIMESTAMP -> attributes.creationTime
        }

    private fun evaluate(expression: String, event: MuseEvent): String =
        expressionEvaluator.evaluate(expression, event)?.getOrNull()?.toString() ?: expression

    private data class InflightDispatch(
        val provider: MuseCursorStreamProvider,
        val attributes: LocalFileAttributes,
    )

    private data class FileFingerprint(
        val size: Long,
        val lastModifiedTime: Instant,
        val creationTime: Instant,
    )

    private data class FileListenerTransaction(
        val path: String,
        val provider: MuseCursorStreamProvider,
        val fingerprint: FileFingerprint,
        val attributes: LocalFileAttributes,
    )
}
