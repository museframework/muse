package dev.museframework.babelfish.exception

import java.nio.file.Path

/** A mapped component has no Kotlin emitter in the configured provider set. */
class MissingEmitterException internal constructor(
    category: String,
    modelType: Class<*>,
    sourceFile: Path?,
    flow: String?,
) : IllegalStateException(buildString {
    append("No $category emitter registered for ${modelType.name}")
    sourceFile?.let { append(" in '$it'") }
    flow?.let { append(" (flow '$it')") }
})
