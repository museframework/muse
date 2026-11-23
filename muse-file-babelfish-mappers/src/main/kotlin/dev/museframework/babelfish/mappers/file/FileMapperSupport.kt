package dev.museframework.babelfish.mappers.file

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import dev.museframework.babelfish.emitter.kotlinStringLiteral
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.model.config.ConfigRef
import dev.museframework.file.api.config.FileConnectionConfig
import java.util.concurrent.TimeUnit

internal fun RawElement.childText(tag: String): String? = children.find { it.tag == tag }?.textContent

internal inline fun <reified T : Enum<T>> String.toFileEnum(): T = enumValueOf(trim().uppercase())

internal fun String.toTimeUnit(): TimeUnit = TimeUnit.valueOf(trim().uppercase())

internal fun fileDsl(name: String): MemberName = MemberName("dev.museframework.file.api.dsl", name)

internal fun connectionName(ref: ConfigRef): CodeBlock {
    val name = when (ref) {
        is ConfigRef.ByName -> ref.name
        is ConfigRef.ByReference -> {
            val config = ref.config as? FileConnectionConfig
            config?.name?.ifBlank { config.docName }.orEmpty()
        }
    }
    return kotlinStringLiteral(name)
}

internal fun CodeBlock.Builder.addString(property: String, value: String?) {
    value?.let { addStatement("%N = %L", property, kotlinStringLiteral(it)) }
}

internal fun CodeBlock.Builder.addLong(property: String, value: Long?) {
    value?.let { addStatement("%N = %L", property, it) }
}

internal fun CodeBlock.Builder.addNonDefaultBoolean(property: String, value: Boolean, default: Boolean) {
    if (value != default) addStatement("%N = %L", property, value)
}

internal fun CodeBlock.Builder.addTimeUnit(property: String, value: TimeUnit?, default: TimeUnit? = null) {
    if (value != null && value != default) addStatement("%N = %T.%N", property, TimeUnit::class, value.name)
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
