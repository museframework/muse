package dev.museframework.e2e.objectstore

import dev.museframework.dsl.api.model.component.Source
import dev.museframework.dsl.api.model.config.MuseConfiguration
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.dsl.dsl.globalProperty
import dev.museframework.e2e.objectstore.generated.ObjectStoreGeneratedConfig
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.source.MuseEventEnvelope
import dev.museframework.engine.api.source.MuseSource
import dev.museframework.engine.api.spi.MuseComponentRegistrar
import dev.museframework.engine.app.MuseApplicationConfig
import dev.museframework.engine.app.bootstrap
import dev.museframework.engine.factory.MuseComponentFactory
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.ServiceLoader
import kotlin.reflect.full.memberProperties

/** Runs the generated application through an in-process source, without a network transport. */
fun main(args: Array<String>) = runBlocking {
    require(args.size == 4) { "Expected scenario, namespace, data directory, output file" }
    val definition = ObjectStoreGeneratedConfig()
    val declarations = definition::class.memberProperties.map { it.getter.call(definition) }
    val flows = declarations.filterIsInstance<MuseFlow.Flow>().map { flow ->
        flow.copy(source = AcceptanceSourceConfig(docId = "${flow.docId}-acceptance-source"))
    }
    val factory = MuseComponentFactory().apply {
        ServiceLoader.load(MuseComponentRegistrar::class.java).forEach { it.register(this) }
        registerSource(AcceptanceSourceConfig::class) { config, _, _ -> AcceptanceSource(config) }
    }
    val configs = listOf(
        globalProperty("muse.object-store.namespace", args[1]),
        globalProperty("muse.object-store.data-dir", args[2]),
    ) + declarations.filterIsInstance<MuseConfiguration>()
    val application = bootstrap(MuseApplicationConfig(flows, configs, factory))
    try {
        application.init()
        application.start()
        val payload: Any = if (args[0] == "cdata-structured") mapOf("id" to 7) else "incoming"
        val envelope = MuseEventEnvelope(MuseEvent(payload = TypedValue.of(payload)))
        application.channelFor(args[0]).send(envelope)
        val reply = withTimeout(10_000) { envelope.replyTo.await() }
        val result = Properties().apply {
            setProperty("payload", printable(reply.payload))
            setProperty("error", reply.error?.errorType?.toString() ?: "none")
            setProperty("storedIsMap", (reply.variables["stored"]?.value is Map<*, *>).toString())
            reply.variables.forEach { (key, value) -> setProperty(key, printable(value)) }
        }
        Files.newOutputStream(Path.of(args[3])).use { result.store(it, null) }
    } finally {
        application.stop()
    }
}

private fun printable(value: Any?): String = materialize(value).toString()

private fun materialize(value: Any?): Any? = when (value) {
    is TypedValue<*> -> materialize(value.value)
    is Map<*, *> -> value.mapValues { materialize(it.value) }
    is List<*> -> value.map(::materialize)
    else -> value
}

private data class AcceptanceSourceConfig(
    override val docId: String,
    override val docName: String = "packaged acceptance source",
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
) : Source

private class AcceptanceSource(config: AcceptanceSourceConfig) : MuseSource, Source by config {
    override suspend fun start(channel: SendChannel<MuseEventEnvelope>): Unit = awaitCancellation()
    override suspend fun stop() = Unit
    override suspend fun onResult(event: MuseEvent, transactionContext: Any?) = Unit
}
