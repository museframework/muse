package dev.museframework.connectors.file.internal

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks connector-held file locks so mutating operations can refuse locked paths,
 * matching Mule File connector behaviour (OS advisory locks alone are not enough on Unix).
 */
internal class FileLockRegistry {
    private val locked = ConcurrentHashMap.newKeySet<Path>()

    fun acquire(path: Path) {
        locked.add(path.toAbsolutePath().normalize())
    }

    fun release(path: Path) {
        locked.remove(path.toAbsolutePath().normalize())
    }

    fun isLocked(path: Path): Boolean = locked.contains(path.toAbsolutePath().normalize())
}
