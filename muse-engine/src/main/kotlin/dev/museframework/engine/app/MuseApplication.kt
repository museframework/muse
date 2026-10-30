package dev.museframework.engine.app

import dev.museframework.dsl.api.model.flow.FlowInitialState
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.engine.api.source.MuseEventEnvelope
import dev.museframework.engine.api.source.MuseSource
import dev.museframework.engine.api.execution.policy.BackgroundExecutionConfig
import dev.museframework.engine.context.MuseContext
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.PipelineScope
import dev.museframework.engine.api.streaming.StreamingEventResources
import dev.museframework.engine.api.streaming.StreamingResourceRegistry
import dev.museframework.engine.execution.terminalContext
import dev.museframework.engine.execution.policy.BackgroundTaskManager
import dev.museframework.engine.dispatcher.CoroutineDispatcherProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class MuseApplication internal constructor(
    val context: MuseContext,
    private val dispatcher: CoroutineDispatcher,
    internal val backgroundTasks: BackgroundTaskManager,
) {
    /** Constructs a standalone application with the default background limits. */
    constructor(
        context: MuseContext,
        dispatcher: CoroutineDispatcher = CoroutineDispatcherProvider.fromSystemProperty(),
    ) : this(context, dispatcher, BackgroundTaskManager(BackgroundExecutionConfig(), dispatcher))

    private val logger = org.slf4j.LoggerFactory.getLogger(MuseApplication::class.java)
    private val factory = context.factory
    private val bindings = context.attachPolicyBindings(context.resolveGlobalHandlerFlow(), backgroundTasks)
    private val activeSources = mutableListOf<Pair<MuseSource, MuseFlow.Flow>>()
    private val channelMap = mutableMapOf<String, Channel<MuseEventEnvelope>>()
    private val sourceScope = CoroutineScope(dispatcher + SupervisorJob())
    private val requestScope = CoroutineScope(dispatcher + SupervisorJob())
    private val sourceJobs = mutableListOf<Job>()
    private val consumerJobs = Collections.synchronizedList(mutableListOf<Job>())
    private val requestJobs = Collections.synchronizedList(mutableListOf<Job>())
    private val completionJob = Job()
    private val stopped = AtomicBoolean(false)
    val expressionEvaluator = context.expressionEvaluator
    private var initialized = false

    suspend fun init() {
        logger.info("MuseApplication.init() called")
        if (initialized) return
        bindings.prepare()
        initialized = true
        printRegisteredFlows()
        context.flows.values
            .filterIsInstance<MuseFlow.Flow>()
            .filter { it.initialState == FlowInitialState.STARTED }
            .forEach { flow ->
                flow.source?.let { source ->
                    val museSource = factory.createSource(source, context, expressionEvaluator)
                    val channel =
                        Channel<MuseEventEnvelope>(Channel.BUFFERED) { envelope ->
                            // A send that never becomes a request owns its claims no longer.
                            releaseQuietly(
                                envelope.executionResources,
                                "an undelivered event must release its execution resources",
                            )
                        }

                    channelMap[flow.name] = channel
                    activeSources.add(museSource to flow)

                    val consumer = sourceScope.launch { launchConsumer(flow, museSource, channel) }
                    consumerJobs += consumer
                    consumer.invokeOnCompletion { consumerJobs.remove(consumer) }
                }
            }
    }

    suspend fun initIfNeeded() {
        logger.info("initIfNeeded() called, initialized=$initialized")
        if (!initialized) init()
    }

    suspend fun start() {
        activeSources.forEach { (museSource, flow) ->
            val channel =
                channelMap[flow.name]
                    ?: throw IllegalStateException("Channel not found for flow '${flow.name}' — was init() called?")
            val sourceJob =
                sourceScope.launch {
                    museSource.start(channel)
                }
            sourceJobs.add(sourceJob)
        }

        sourceScope.launch {
            sourceJobs.joinAll()
            completionJob.complete()
        }
    }

    fun channelFor(flowName: String): Channel<MuseEventEnvelope> =
        channelMap[flowName]
            ?: throw IllegalStateException("No channel for flow '$flowName' — was init() called?")

    suspend fun awaitCompletion() {
        completionJob.join()
    }

    /**
     * Stop closes admission before any drain and never releases application resources before the
     * work they depend on has terminated:
     *
     * 1. background admission and external source admission close;
     * 2. request queues close, queued events that will never execute release their claims, and the
     *    queue consumers are cancelled and joined, so no request can be published after this point;
     * 3. queued request and accepted background work drain for the configured grace period;
     * 4. unfinished work is cancelled and joined, request queue consumers included;
     * 5. only then are the application's prepared bindings released.
     *
     * Repeated stop is idempotent.
     */
    suspend fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        backgroundTasks.closeAdmission()
        activeSources.forEach { (source, _) ->
            runCatching { source.stop() }.onFailure { failure ->
                logger.error("Failed to stop source {}", source.docName, failure)
            }
        }
        channelMap.values.forEach { it.close() }
        drainQueuedEnvelopes()
        // Cancel and join the queue consumers before the first stable request snapshot: once no
        // consumer can publish, nothing can enter the ledger behind the drain.
        val consumers = synchronized(consumerJobs) { consumerJobs.toList() }
        consumers.forEach { it.cancel() }
        consumers.joinAll()
        sourceScope.cancel()

        withTimeoutOrNull(backgroundTasks.graceMillis) {
            coroutineScope {
                launch { pendingRequests().joinAll() }
                launch { backgroundTasks.awaitSettled() }
            }
        }

        val unfinished = pendingRequests()
        unfinished.forEach { it.cancel() }
        unfinished.joinAll()
        backgroundTasks.settleNow()
        bindings.close()
        activeSources.clear()
        channelMap.clear()
        sourceJobs.clear()
        completionJob.complete()
    }

    /**
     * Consumes one flow's event queue.
     *
     * Every received envelope immediately becomes a request job: there is no suspension between
     * taking the event off the queue and owning it, so a stopping consumer can never drop an event.
     */
    private suspend fun launchConsumer(
        flow: MuseFlow.Flow,
        source: MuseSource,
        channel: Channel<MuseEventEnvelope>,
    ) {
        val semaphore = flow.maxConcurrency?.let { Semaphore(it) }

        for (envelope in channel) {
            // Lazy start: the request owns its envelope before it runs, so a job cancelled before
            // start still releases the claims of the event it took from the queue.
            val request =
                requestScope.launch(start = CoroutineStart.LAZY) {
                    // The request job watches the source's cancellation signal and cancels only
                    // this request's execution; accepted background work is never propagated to.
                    val execution = launch { executeEnvelope(flow, source, envelope, semaphore) }
                    envelope.requestCancellation?.let { signal ->
                        val watcher =
                            launch {
                                try {
                                    signal.await()
                                } finally {
                                    execution.cancel()
                                }
                            }
                        execution.invokeOnCompletion { watcher.cancel() }
                    }
                    execution.join()
                }
            // Idempotent with the request's own finalization: no received event keeps its claims.
            request.invokeOnCompletion { envelope.executionResources.releaseAll() }
            requestJobs += request
            request.invokeOnCompletion { requestJobs.remove(request) }
            request.start()
        }
    }

    private suspend fun CoroutineScope.executeEnvelope(
        flow: MuseFlow.Flow,
        source: MuseSource,
        envelope: MuseEventEnvelope,
        semaphore: Semaphore?,
    ) {
        val permit = semaphore?.also { it.acquire() }
        try {
            val executionContext =
                ExecutionContext(
                    event = envelope.event,
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                    streamingResources = envelope.executionResources,
                    currentScope = PipelineScope.FlowScope(flow),
                )
            StreamingEventResources.registerEventProviders(envelope.event, envelope.executionResources)
            val result = bindings.execution(flow).invoke(executionContext)
            val finalEvent = result.terminalContext().event

            // The reply's claim exists before the source can observe the completed reply.
            StreamingEventResources.registerEventProviders(finalEvent, envelope.executionResources)
            envelope.replyResources?.let { StreamingEventResources.registerEventProviders(finalEvent, it) }
            envelope.replyTo.complete(finalEvent)
            source.onResult(finalEvent, envelope.transactionContext)
        } catch (failure: Throwable) {
            envelope.replyTo.completeExceptionally(failure)
        } finally {
            finalizeRequest(envelope, permit)
        }
    }

    /**
     * Idempotent request finalization, independent of its caller's cancellation: the request's
     * claims and its flow concurrency permit are released even when the request was cancelled.
     */
    private suspend fun finalizeRequest(
        envelope: MuseEventEnvelope,
        permit: Semaphore?,
    ) = withContext(NonCancellable) {
        releaseQuietly(envelope.executionResources, "a finished request must release its execution resources")
        permit?.release()
    }

    private fun pendingRequests(): List<Job> = synchronized(requestJobs) { requestJobs.toList() }

    /** Releases claims of events that were queued but will never execute. */
    private fun drainQueuedEnvelopes() {
        channelMap.values.forEach { channel ->
            while (true) {
                val queued = channel.tryReceive()
                val envelope = queued.getOrNull() ?: break
                releaseQuietly(
                    envelope.executionResources,
                    "a queued event that never executed must release its execution resources",
                )
            }
        }
    }

    private fun releaseQuietly(
        registry: StreamingResourceRegistry,
        message: String,
    ) {
        try {
            registry.releaseAll()
        } catch (failure: Throwable) {
            logger.error(message, failure)
        }
    }

    private fun printRegisteredFlows() {
        if (context.flows.isNotEmpty()) {
            logger.info("")
            logger.info("╔══════════════════════════════════════════════════════════════╗")
            logger.info("║                    REGISTERED FLOWS                          ║")
            logger.info("╠══════════════════════════════════════════════════════════════╣")
            context.flows.forEach { (name, flow) ->
                val flowType =
                    when (flow) {
                        is MuseFlow.Flow -> "Flow"
                        is MuseFlow.SubFlow -> "SubFlow"
                        else -> "Unknown"
                    }
                val sourceInfo =
                    if (flow is MuseFlow.Flow && flow.source != null) {
                        " [with source]"
                    } else {
                        ""
                    }
                val initialState =
                    if (flow is MuseFlow.Flow) {
                        " (${flow.initialState})"
                    } else {
                        ""
                    }
                logger.info("║  • $name")
                logger.info("║    └─ Type: $flowType$initialState$sourceInfo")
            }
            logger.info("╚══════════════════════════════════════════════════════════════╝")
            logger.info("")
        } else {
            logger.warn("No flows registered")
        }
    }
}
