package dev.museframework.babelfish.mappers.objectstore

import dev.museframework.babelfish.api.ConfigMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ConfigCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawConfig
import dev.museframework.objectstore.api.config.ObjectStoreConfig
import dev.museframework.objectstore.api.config.ObjectStoreConnectorConfig
import dev.museframework.objectstore.api.config.ObjectStoreConnectorRef
import java.time.Duration
import java.time.temporal.ChronoUnit

object ObjectStoreConnectorConfigMapper : ConfigMapper {
    override fun map(config: RawConfig, ctx: MappingContext): ObjectStoreConnectorConfig = withLocation(config.tag, config.location, ctx) {
        validateAttributes(config.attrs, setOf("name", "lockTimeout", "lockTimeoutUnit"))
        require(config.children.size <= 1) { "Only one default os:connection is supported" }
        config.children.forEach {
            require(it.namespace == "os" && it.tag == "connection" && it.attrs.isEmpty() && it.children.isEmpty()) {
                "Only an empty default os:connection is supported; custom providers and reconnection settings are unsupported"
            }
        }
        val name = config.attrs.required("name")
        require(config.id !in ctx.configs) { "Duplicate global configuration name '$name'" }
        ObjectStoreConnectorConfig(
            name = name,
            lockTimeout = config.attrs.duration("lockTimeout", ChronoUnit.SECONDS),
            docName = config.attrs["doc:name"] ?: name,
            docDescription = config.attrs["doc:description"],
            sourceFile = ctx.sourceFile.toString(),
            sourceLine = config.location.line,
        )
    }
}

object ObjectStoreConfigMapper : ConfigMapper {
    override fun map(config: RawConfig, ctx: MappingContext): ObjectStoreConfig = withLocation(config.tag, config.location, ctx) {
        validateAttributes(config.attrs, setOf("name", "config-ref", "persistent", "maxEntries", "entryTtl", "entryTtlUnit", "expirationInterval", "expirationIntervalUnit"))
        require(config.children.isEmpty()) { "Nested/private Object Stores and custom provider configurations are unsupported" }
        val name = config.attrs.required("name")
        require(config.id !in ctx.configs) { "Duplicate global configuration name '$name'" }
        ObjectStoreConfig(
            name = name,
            connector = config.attrs["config-ref"]?.let(ObjectStoreConnectorRef::ByName) ?: ObjectStoreConnectorRef.Default,
            persistent = config.attrs.boolean("persistent", true),
            maxEntries = config.attrs["maxEntries"]?.let { it.toIntOrNull() ?: throw IllegalArgumentException("maxEntries must be an integer in range") },
            entryTtl = config.attrs.duration("entryTtl", ChronoUnit.SECONDS),
            expirationInterval = config.attrs.duration("expirationInterval", ChronoUnit.MINUTES) ?: Duration.ofMinutes(1),
            docName = config.attrs["doc:name"] ?: name,
            docDescription = config.attrs["doc:description"],
            sourceFile = ctx.sourceFile.toString(),
            sourceLine = config.location.line,
        )
    }
}

object ObjectStoreConnectorConfigCodeEmitter : ConfigCodeEmitter<ObjectStoreConnectorConfig> {
    override fun emit(config: ObjectStoreConnectorConfig, ctx: CodegenContext): String {
        ctx.addImport("dev.museframework.objectstore.api.dsl.objectStoreConnector")
        return buildString {
            appendLine("objectStoreConnector {")
            assignment("name", config.name, ctx)
            assignment("docName", config.docName, ctx)
            assignment("docDescription", config.docDescription, ctx)
            durationAssignment("lockTimeout", config.lockTimeout, ctx)
            append("}")
        }
    }
}

object ObjectStoreConfigCodeEmitter : ConfigCodeEmitter<ObjectStoreConfig> {
    override fun emit(config: ObjectStoreConfig, ctx: CodegenContext): String {
        ctx.addImport("dev.museframework.objectstore.api.dsl.objectStore")
        return buildString {
            appendLine("objectStore {")
            assignment("name", config.name, ctx)
            assignment("docName", config.docName, ctx)
            assignment("docDescription", config.docDescription, ctx)
            config.connector.expression(ctx)?.let { appendLine("    connector = $it") }
            if (!config.persistent) appendLine("    persistent = false")
            config.maxEntries?.let { appendLine("    maxEntries = $it") }
            durationAssignment("entryTtl", config.entryTtl, ctx)
            if (config.expirationInterval != Duration.ofMinutes(1)) durationAssignment("expirationInterval", config.expirationInterval, ctx)
            append("}")
        }
    }
}
