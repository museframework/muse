package dev.museframework.babelfish.exception

import dev.museframework.babelfish.model.SourceLocation
import java.nio.file.Path

class UnmappedElementException(
    val namespace: String,
    val tag: String,
    val location: SourceLocation,
    val sourceFile: Path
) : Exception(
    "No mapper registered for '${if (namespace.isNotEmpty()) "$namespace:" else ""}$tag' " +
    "at $location in ${sourceFile.fileName}"
)
