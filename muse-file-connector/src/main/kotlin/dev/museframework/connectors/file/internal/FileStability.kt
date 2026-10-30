package dev.museframework.connectors.file.internal

import dev.museframework.file.api.model.FileErrorType
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Performs Mule-style size stability checks. Call only when file content is about to be retrieved.
 */
internal fun awaitStableSize(path: Path, interval: Long?, unit: TimeUnit) {
    if (interval == null) return
    val initialSize = Files.size(path)
    val nanos = unit.toNanos(interval)
    if (nanos > 0) {
        val millis = nanos / 1_000_000
        val remainder = (nanos % 1_000_000).toInt()
        Thread.sleep(millis, remainder)
    }
    val finalSize = Files.size(path)
    if (initialSize != finalSize) {
        throw FileConnectorException(
            FileErrorType.ANY,
            "File '$path' changed size while waiting for it to become stable",
        )
    }
}
