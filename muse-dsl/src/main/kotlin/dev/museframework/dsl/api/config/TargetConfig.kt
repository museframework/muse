package dev.museframework.dsl.api.config

data class TargetConfig(
    val target: String,
    val targetValue: String? = null,
) {
    companion object {
        fun of(target: String): TargetConfig = TargetConfig(target = target)

        fun of(
            target: String,
            targetValue: String,
        ): TargetConfig = TargetConfig(target = target, targetValue = targetValue)
    }
}
