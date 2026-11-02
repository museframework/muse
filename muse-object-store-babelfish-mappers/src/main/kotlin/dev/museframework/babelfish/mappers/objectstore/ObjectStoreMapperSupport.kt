package dev.museframework.babelfish.mappers.objectstore

import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.babelfish.model.SourceLocation
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.objectstore.api.config.ObjectStoreConnectorRef
import dev.museframework.objectstore.api.config.ObjectStoreRef
import java.time.Duration
import java.time.temporal.ChronoUnit

internal val metadataAttributes = setOf("doc:name", "doc:id", "doc:description")

internal inline fun <T> withLocation(tag: String, location: SourceLocation, ctx: MappingContext, action: () -> T): T =
    try {
        action()
    } catch (cause: IllegalArgumentException) {
        throw IllegalArgumentException("Cannot translate os:$tag at ${location.line}:${location.col} (${location}) in ${ctx.sourceFile.fileName}: ${cause.message}", cause)
    } catch (cause: ArithmeticException) {
        throw IllegalArgumentException("Cannot translate os:$tag at ${location.line}:${location.col} (${location}) in ${ctx.sourceFile.fileName}: duration exceeds the supported range", cause)
    }

internal fun validateAttributes(attrs: Map<String, String>, supported: Set<String>) {
    val unsupported = attrs.keys - supported - metadataAttributes
    require(unsupported.isEmpty()) { "Unsupported attribute(s): ${unsupported.sorted().joinToString()}; only the local Object Store provider is supported" }
}

internal fun RawElement.validateShape(supported: Set<String>, childTags: Set<String> = emptySet()) {
    validateAttributes(attrs, supported + "objectStore")
    children.forEach { child ->
        require(child.namespace == "os" && child.tag in childTags) {
            "Unsupported child ${child.namespace}:${child.tag}; private stores and custom providers are not supported"
        }
        require(child.children.isEmpty() && child.attrs.isEmpty()) { "os:${child.tag} must contain only expression text" }
    }
    require(children.map { it.tag }.distinct().size == children.size) { "Duplicate expression children are not supported" }
}

internal fun Map<String, String>.required(name: String): String =
    get(name)?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("Required attribute '$name' is missing or empty")

internal fun Map<String, String>.boolean(name: String, default: Boolean): Boolean =
    get(name)?.let { it.toBooleanStrictOrNull() ?: throw IllegalArgumentException("$name must be true or false") } ?: default

internal fun Map<String, String>.duration(name: String, defaultUnit: ChronoUnit): Duration? {
    val unitText = get("${name}Unit")
    val unit = unitText?.let {
        when (it.uppercase()) {
            "NANOSECONDS" -> ChronoUnit.NANOS
            "MICROSECONDS" -> ChronoUnit.MICROS
            "MILLISECONDS" -> ChronoUnit.MILLIS
            "SECONDS" -> ChronoUnit.SECONDS
            "MINUTES" -> ChronoUnit.MINUTES
            "HOURS" -> ChronoUnit.HOURS
            "DAYS" -> ChronoUnit.DAYS
            else -> throw IllegalArgumentException("Unsupported ${name}Unit '$it'")
        }
    } ?: defaultUnit
    return get(name)?.let { text ->
        val amount = text.toLongOrNull() ?: throw IllegalArgumentException("$name must be an integer")
        Duration.of(amount, unit)
    }
}

internal fun RawElement.storeRef(): ObjectStoreRef = attrs["objectStore"]?.let(ObjectStoreRef::ByName) ?: ObjectStoreRef.Default

internal fun RawElement.target(): TargetConfig? {
    require(attrs["targetValue"] == null || attrs["target"] != null) { "targetValue requires target" }
    return attrs["target"]?.let { TargetConfig(it, attrs["targetValue"]) }
}

internal fun RawElement.expression(name: String): String? {
    val child = children.singleOrNull { it.tag == name }?.textContent
    require(child == null || attrs[name] == null) { "Specify $name either as attribute or child, not both" }
    return child ?: attrs[name]
}

internal fun CodegenContext.quoted(value: String): String = buildString {
    append('"')
    escapeKotlinString(value).forEach { char ->
        when (char) {
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char.code < 32) append("\\u${char.code.toString(16).padStart(4, '0')}") else append(char)
        }
    }
    append('"')
}

internal fun StringBuilder.assignment(name: String, value: String?, ctx: CodegenContext) {
    value?.let { appendLine("    $name = ${ctx.quoted(it)}") }
}

internal fun StringBuilder.durationAssignment(name: String, value: Duration?, ctx: CodegenContext) {
    value?.let {
        ctx.addImport("java.time.Duration")
        appendLine("    $name = Duration.ofNanos(${it.toNanos()}L)")
    }
}

internal fun StringBuilder.targetAssignment(target: TargetConfig?, ctx: CodegenContext) {
    target ?: return
    ctx.addImport("dev.museframework.dsl.api.config.TargetConfig")
    append("    targetConfig = TargetConfig(${ctx.quoted(target.target)}")
    target.targetValue?.let { append(", ${ctx.quoted(it)}") }
    appendLine(")")
}

internal fun ObjectStoreRef.expression(ctx: CodegenContext): String = when (this) {
    ObjectStoreRef.Default -> ""
    is ObjectStoreRef.ByName -> {
        ctx.addImport("dev.museframework.objectstore.api.dsl.objectStore")
        "objectStore(${ctx.quoted(name)})"
    }
    is ObjectStoreRef.ByReference -> ObjectStoreRef.ByName(config.name).expression(ctx)
}

internal fun ObjectStoreConnectorRef.expression(ctx: CodegenContext): String? = when (this) {
    ObjectStoreConnectorRef.Default -> null
    is ObjectStoreConnectorRef.ByName -> {
        ctx.addImport("dev.museframework.objectstore.api.dsl.objectStoreConnector")
        "objectStoreConnector(${ctx.quoted(name)})"
    }
    is ObjectStoreConnectorRef.ByReference -> ObjectStoreConnectorRef.ByName(config.name).expression(ctx)
}
