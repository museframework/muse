package dev.museframework.babelfish.mappers.sftp

import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.sftp.api.config.SftpConnectionRef
import java.util.concurrent.TimeUnit

internal fun RawElement.childText(tag: String): String? = children.find { it.tag == tag }?.textContent

internal fun RawElement.mapTargetConfig(): TargetConfig? =
    attr("target")?.let { target ->
        TargetConfig(target = target, targetValue = attr("targetValue"))
    }

internal fun StringBuilder.appendTargetConfig(
    targetConfig: TargetConfig?,
    ctx: CodegenContext,
    indent: String = "    ",
) {
    val target = targetConfig ?: return
    ctx.addImport("dev.museframework.dsl.api.config.TargetConfig")
    append("${indent}targetConfig = TargetConfig(\"${ctx.escapeKotlinString(target.target)}\"")
    target.targetValue?.let { append(", \"${ctx.escapeKotlinString(it)}\"") }
    appendLine(")")
}

internal inline fun <reified T : Enum<T>> String.toSftpEnum(): T = enumValueOf(trim().uppercase())

internal fun String.toTimeUnit(): TimeUnit = TimeUnit.valueOf(trim().uppercase())

internal fun durationToMillis(
    value: String?,
    unitText: String?,
    defaultUnit: TimeUnit = TimeUnit.SECONDS,
): Long? {
    val amount = value?.toLongOrNull() ?: return null
    val unit = unitText?.toTimeUnit() ?: defaultUnit
    return unit.toMillis(amount)
}

internal fun connectionRefExpression(
    ref: SftpConnectionRef,
    ctx: CodegenContext,
): String =
    when (ref) {
        is SftpConnectionRef.ByName -> "sftpConnection(\"${ctx.escapeKotlinString(ref.name)}\")"
        is SftpConnectionRef.ByReference -> {
            val config = ref.config
            val name = config.name.ifBlank { config.docName }
            "sftpConnection(\"${ctx.escapeKotlinString(name)}\")"
        }
    }

internal fun connectionNameExpression(
    ref: SftpConnectionRef,
    ctx: CodegenContext,
): String =
    when (ref) {
        is SftpConnectionRef.ByName -> "\"${ctx.escapeKotlinString(ref.name)}\""
        is SftpConnectionRef.ByReference -> {
            val config = ref.config
            val name = config.name.ifBlank { config.docName }
            "\"${ctx.escapeKotlinString(name)}\""
        }
    }

internal fun StringBuilder.appendNonDefaultBoolean(
    property: String,
    value: Boolean,
    default: Boolean,
    indent: String = "    ",
) {
    if (value != default) appendLine("$indent$property = $value")
}

internal fun StringBuilder.appendOptionalString(
    property: String,
    value: String?,
    ctx: CodegenContext,
    indent: String = "    ",
) {
    value?.let { appendLine("$indent$property = \"${ctx.escapeKotlinString(it)}\"") }
}

internal fun StringBuilder.appendOptionalLong(
    property: String,
    value: Long?,
    indent: String = "    ",
) {
    value?.let { appendLine("$indent$property = $it") }
}

internal fun StringBuilder.appendNonDefaultLong(
    property: String,
    value: Long,
    default: Long,
    indent: String = "    ",
) {
    if (value != default) appendLine("$indent$property = $value")
}

internal fun StringBuilder.appendNonDefaultInt(
    property: String,
    value: Int,
    default: Int,
    indent: String = "    ",
) {
    if (value != default) appendLine("$indent$property = $value")
}

internal fun StringBuilder.appendNonDefaultString(
    property: String,
    value: String,
    default: String,
    ctx: CodegenContext,
    indent: String = "    ",
) {
    if (value != default) appendLine("$indent$property = \"${ctx.escapeKotlinString(value)}\"")
}

internal fun pollIntervalMs(element: RawElement): Long {
    val scheduling = element.children.find { it.tag == "scheduling-strategy" }
    if (scheduling?.children?.any { it.tag == "cron" } == true) {
        throw IllegalArgumentException(
            "cron scheduling is not supported for the SFTP listener; use fixed-frequency",
        )
    }
    val fixed = scheduling?.children?.find { it.tag == "fixed-frequency" }
    val frequencyText = fixed?.attrs?.get("frequency")
    if (frequencyText != null) {
        val frequency =
            frequencyText.toLongOrNull()?.takeIf { it > 0 }
                ?: throw IllegalArgumentException(
                    "fixed-frequency frequency must be a positive integer but was '$frequencyText'",
                )
        val unit = fixed.attrs["timeUnit"]?.toTimeUnit() ?: TimeUnit.MILLISECONDS
        return unit.toMillis(frequency)
    }
    return element.attrs["pollingFrequency"]?.toLongOrNull() ?: 1_000L
}
