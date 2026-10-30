package dev.museframework.babelfish.exception

import java.nio.file.Path

class BabelfishTranslationException(
    val errors: List<UnmappedElementException>
) : Exception(
    "Translation failed with ${errors.size} unmapped element(s):\n" +
    errors.joinToString("\n") { "  - ${it.message}" }
)

class BabelfishParseException(
    val sourceFile: Path,
    cause: Throwable
) : Exception("Failed to parse Mule XML at ${sourceFile.fileName}", cause)
