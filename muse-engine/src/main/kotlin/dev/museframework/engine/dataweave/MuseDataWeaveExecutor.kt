package dev.museframework.engine.dataweave

import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.expression.ExpressionIteration
import dev.museframework.engine.api.expression.ExpressionSplit
import dev.museframework.engine.api.streaming.StreamingCleanupException
import org.mule.weave.v2.runtime.ExecutableWeave
import org.mule.weave.v2.parser.ast.structure.DocumentNode
import org.mule.weave.v2.parser.ast.variables.NameIdentifier
import org.mule.weave.v2.runtime.DataWeaveResult
import org.mule.weave.v2.runtime.DataWeaveScript
import org.mule.weave.v2.runtime.ParserConfigurationBuilder
import org.mule.weave.v2.runtime.ScriptingBindings
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap


class MuseDataWeaveExecutor : DataWeaveExecutor {

    private val parserConfig = ParserConfigurationBuilder()
        .addImplicitImport("dw::Muse::Runtime")
        .build()

    private val scriptingEngine = DataWeaveEngineFactory.create(parserConfig)

    private val compiledScripts = ConcurrentHashMap<String, DataWeaveScript>()

    private val splitScripts = ConcurrentHashMap<String, ExecutableWeave<DocumentNode>>()

    override fun split(expression: String, context: DataWeaveContext): ExpressionSplit =
        MuseProperties.withProperties(context.properties) {
            val ownedBindings = mutableListOf<AutoCloseable>()
            try {
                val executable = splitScripts.computeIfAbsent(expression) { DataWeaveSplitter.compile(it) }
                val reader = DataWeaveSplitter.open(executable, createBindings(context, split = true, ownedBindings))
                val iterator = object : Iterator<TypedValue<Any>>, AutoCloseable {
                    private var closed = false
                    override fun hasNext(): Boolean = MuseProperties.withProperties(context.properties) { reader.hasNext() }
                    override fun next(): TypedValue<Any> = MuseProperties.withProperties(context.properties) {
                        val item = reader.next()
                        TypedValue.of(item.value(), item.mime(), item.charset())
                    }
                    override fun close() = MuseProperties.withProperties(context.properties) {
                        if (!closed) {
                            closed = true
                            closeSplitBindings(reader as AutoCloseable, ownedBindings)
                        }
                    }
                }
                ExpressionSplit.owned(iterator)
            } catch (failure: Throwable) {
                try { closeSplitBindings(null, ownedBindings) } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
                if (failure !is Exception) throw failure
                throw DataWeaveException("Failed to split expression: $expression", failure)
            }
        }

    private fun closeSplitBindings(reader: AutoCloseable?, ownedBindings: List<AutoCloseable>) {
        val failures = mutableListOf<Throwable>()
        try { reader?.close() } catch (failure: Throwable) { failures += failure }
        ownedBindings.asReversed().forEach {
            try { it.close() } catch (failure: Throwable) { failures += failure }
        }
        if (failures.isNotEmpty()) throw StreamingCleanupException(failures)
    }

    override fun tryOpenIteration(expression: String, context: DataWeaveContext): ExpressionIteration? {
        if (expression.trim() != "payload") return null
        val payload = context.event.payload ?: return null
        if (!payload.mimeType.substringBefore(';').trim().equals("application/csv", ignoreCase = true)) return null
        val metadata = try {
            DataWeaveInputMetadata.parse(payload.mimeType)
        } catch (failure: Exception) {
            throw DataWeaveException("Failed to parse iteration input MIME type", failure)
        }
        if (metadata.mimeType != "application/csv" || !metadata.streaming) return null
        // A local array alias memoizes the CSV Scala Stream and retains consumed rows.
        val value = evaluate("payload as Array {iterator: true}", context)?.value
        val iterator = value as? Iterator<*>
            ?: throw DataWeaveException("Iteration evaluation did not return an iterator")
        if (iterator !is AutoCloseable) throw DataWeaveException("Streaming iteration is not closeable")
        return ExpressionIteration.owned(iterator)
    }

    override fun evaluate(expression: String, context: DataWeaveContext): TypedValue<Any>? =
        MuseProperties.withProperties(context.properties) {
            try {
                val script = compiledScripts.getOrPut(expression.hashCode().toString()) {
                    val config = scriptingEngine.newConfig()
                        .withScript(expression)
                        .withInputs(arrayOf("payload", "vars", "attributes", "correlationId", "error"))
                        .withDefaultOutputType(TypedValue.MIME_JAVA)
                    scriptingEngine.compileWith(config)
                }
                executeAndExtract(script, context)
            } catch (e: Exception) {
                throw DataWeaveException("Failed to evaluate expression: $expression", e)
            }
        }


    override fun evaluateResource(resourcePath: String, context: DataWeaveContext): TypedValue<Any>? =
        MuseProperties.withProperties(context.properties) {
            try {
                val script = compiledScripts.getOrPut("resource:$resourcePath") {
                    val file = File(resourcePath)
                    val sourceConfig = if (file.isFile) scriptingEngine.newConfig().withFile(file)
                    else scriptingEngine.newConfig().withNameIdentifier(NameIdentifier.fromPath(resourcePath.trimStart('/')))
                    val config = sourceConfig
                        .withInputs(arrayOf("payload", "vars", "attributes", "correlationId", "error"))
                        .withDefaultOutputType(TypedValue.MIME_JAVA)
                    scriptingEngine.compileWith(config)
                }
                executeAndExtract(script, context)
            } catch (e: Exception) {
                throw DataWeaveException("Failed to evaluate resource: $resourcePath", e)
            }
        }


    private fun executeAndExtract(script: DataWeaveScript, context: DataWeaveContext): TypedValue<Any>? {
        val bindings = createBindings(context)
        val result = script.write(bindings)
        return extractResult(result)
    }

    private fun extractResult(result: DataWeaveResult): TypedValue<Any>? {
        return TypedValue.of(result.content, result.mimeType, result.charset)
    }

    private fun createBindings(
        context: DataWeaveContext,
        split: Boolean = false,
        ownedBindings: MutableList<AutoCloseable>? = null,
    ): ScriptingBindings {
        val bindings = ScriptingBindings()

        val payload = context.event.payload
        val payloadMime = payload?.mimeType?.takeIf { it != TypedValue.MIME_ANY } ?: TypedValue.MIME_JAVA
        if (split || payloadMime.substringBefore(';').trim().equals("application/csv", ignoreCase = true)) {
            bindings.addBinding("payload", DataWeaveInputMetadata.parse(payloadMime).binding(
                PayloadBindingValueFactory.payloadValue(context.event, ownedBindings), payload?.charset ?: Charsets.UTF_8,
            ))
        } else {
            bindings.addBinding("payload", PayloadBindingValueFactory.payloadValue(context.event, ownedBindings), payloadMime, hashMapOf())
        }

        bindings.addBinding(
            "vars",
            context.event.variables.mapValues { PayloadBindingValueFactory.typedValue(it.value, ownedBindings) },
            "application/java",
            hashMapOf()
        )

        val attributes = context.event.attributes
        val attributesMime = attributes?.mimeType?.takeIf { it != TypedValue.MIME_ANY } ?: TypedValue.MIME_JAVA
        if (split || attributesMime.substringBefore(';').trim().equals("application/csv", ignoreCase = true)) {
            bindings.addBinding("attributes", DataWeaveInputMetadata.parse(attributesMime).binding(
                PayloadBindingValueFactory.typedValue(attributes, ownedBindings), attributes?.charset ?: Charsets.UTF_8,
            ))
        } else {
            bindings.addBinding("attributes", PayloadBindingValueFactory.typedValue(attributes, ownedBindings), attributesMime, hashMapOf())
        }

        bindings.addBinding(
            "correlationId",
            context.event.correlationId,
            "application/java",
            hashMapOf()
        )

        val errorBinding = context.event.error?.let { error ->
            mapOf(
                "description" to error.description,
                "detailedDescription" to error.detailedDescription,
                "errorType" to mapOf(
                    "namespace" to error.errorType.namespace,
                    "identifier" to error.errorType.identifier,
                ),
                "cause" to error.cause,
                "errorMessage" to error.errorMessage,
                "childErrors" to error.childErrors,
            )
        }
        bindings.addBinding(
            "error",
            errorBinding,
            "application/java",
            hashMapOf()
        )

        return bindings
    }

    private fun loadResource(path: String): InputStream {
        return javaClass.getResourceAsStream(path)
            ?: javaClass.getClassLoader().getResourceAsStream(path)
            ?: throw IllegalArgumentException("Resource not found: $path")
    }
}
