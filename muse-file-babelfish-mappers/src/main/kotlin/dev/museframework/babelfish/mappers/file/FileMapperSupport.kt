package dev.museframework.babelfish.mappers.file

import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.model.config.ConfigRef
import dev.museframework.file.api.config.FileConnectionConfig
import java.util.concurrent.TimeUnit

internal fun RawElement.childText(tag: String): String? = children.find { it.tag == tag }?.textContent

internal inline fun <reified T : Enum<T>> String.toFileEnum(): T = enumValueOf(trim().uppercase())

internal fun String.toTimeUnit(): TimeUnit = TimeUnit.valueOf(trim().uppercase())

internal fun connectionRefExpression(ref: ConfigRef, ctx: CodegenContext): String =
    when (ref) {
        is ConfigRef.ByName -> "fileConnection(\"${ctx.escapeKotlinString(ref.name)}\")"
        is ConfigRef.ByReference -> {
            val config = ref.config as? FileConnectionConfig
            val name = config?.name?.ifBlank { config.docName } ?: config?.docName.orEmpty()
            "fileConnection(\"${ctx.escapeKotlinString(name)}\")"
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

internal fun StringBuilder.appendTimeUnit(
    property: String,
    value: TimeUnit?,
    default: TimeUnit? = null,
    indent: String = "    ",
) {
    if (value != null && value != default) {
        appendLine("$indent$property = TimeUnit.$value")
    }
}

internal fun pollingFrequencyMillis(element: RawElement): Long {
    val fixed = fixedFrequency(element)
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

internal fun startDelayMillis(element: RawElement): Long {
    val fixed = fixedFrequency(element) ?: return 0L
    val delayText = fixed.attrs["startDelay"] ?: return 0L
    val delay =
        delayText.toLongOrNull()?.takeIf { it >= 0 }
            ?: throw IllegalArgumentException(
                "fixed-frequency startDelay must be a non-negative integer but was '$delayText'",
            )
    val unit = fixed.attrs["timeUnit"]?.toTimeUnit() ?: TimeUnit.MILLISECONDS
    return unit.toMillis(delay)
}

private fun fixedFrequency(element: RawElement): RawElement? {
    val scheduling = element.children.find { it.tag == "scheduling-strategy" }
    if (scheduling?.children?.any { it.tag == "cron" } == true) {
        throw IllegalArgumentException(
            "cron scheduling is not supported for the file listener; use fixed-frequency",
        )
    }
    return scheduling?.children?.find { it.tag == "fixed-frequency" }
}
