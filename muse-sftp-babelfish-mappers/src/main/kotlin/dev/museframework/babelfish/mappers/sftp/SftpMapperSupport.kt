package dev.museframework.babelfish.mappers.sftp

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import dev.museframework.babelfish.emitter.kotlinStringLiteral
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.sftp.api.config.SftpConnectionRef
import java.util.concurrent.TimeUnit

internal fun RawElement.childText(tag: String): String? = children.find { it.tag == tag }?.textContent

internal fun RawElement.mapTargetConfig(): TargetConfig? =
    attr("target")?.let { target ->
        TargetConfig(target = target, targetValue = attr("targetValue"))
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

internal fun sftpDsl(name: String): MemberName = MemberName("dev.museframework.sftp.api.dsl", name)

internal fun connectionName(ref: SftpConnectionRef): CodeBlock = kotlinStringLiteral(
    when (ref) {
        is SftpConnectionRef.ByName -> ref.name
        is SftpConnectionRef.ByReference -> ref.config.name.ifBlank { ref.config.docName }
    },
)

internal fun CodeBlock.Builder.addString(property: String, value: String?) {
    value?.let { addStatement("%N = %L", property, kotlinStringLiteral(it)) }
}

internal fun CodeBlock.Builder.addNonDefaultBoolean(property: String, value: Boolean, default: Boolean) {
    if (value != default) addStatement("%N = %L", property, value)
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
