package dev.museframework.connectors.file.internal

import dev.museframework.connectors.file.streaming.LocalFileCursorStreamProvider
import dev.museframework.engine.api.streaming.MuseCursorStreamProvider
import dev.museframework.engine.api.streaming.StreamingProviders
import dev.museframework.file.api.config.FileConnectionConfig
import dev.museframework.file.api.model.*
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Clock
import java.time.Instant
import java.util.concurrent.TimeUnit

internal data class FileReadResult(
    val provider: MuseCursorStreamProvider,
    val attributes: LocalFileAttributes,
    val mimeType: String,
)

internal class LocalFileSystem(
    config: FileConnectionConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    val basePath: Path =
        resolveBasePath(config.workingDirectory)

    internal val locks = FileLockRegistry()

    init {
        if (!Files.exists(basePath)) {
            throw FileConnectorException(
                FileErrorType.FILE_DOESNT_EXIST,
                "Working directory '$basePath' does not exist",
            )
        }
        if (!Files.isDirectory(basePath)) {
            throw FileConnectorException(
                FileErrorType.FILE_IS_NOT_DIRECTORY,
                "Working directory '$basePath' is not a directory",
            )
        }
    }

    fun read(
        pathExpression: String,
        lock: Boolean,
        timeBetweenSizeCheck: Long?,
        timeBetweenSizeCheckUnit: TimeUnit,
        outputMimeType: String?,
    ): FileReadResult {
        val path = existingPath(pathExpression)
        if (Files.isDirectory(path)) {
            throw FileConnectorException(FileErrorType.ILLEGAL_PATH, "Cannot read directory '$path'")
        }
        val attributes = attributes(path)
        val provider =
            StreamingProviders.managed(
                LocalFileCursorStreamProvider(
                    file = path,
                    lock = lock,
                    timeBetweenSizeCheck = timeBetweenSizeCheck,
                    timeBetweenSizeCheckUnit = timeBetweenSizeCheckUnit,
                    lockRegistry = locks,
                ),
            )
        val mimeType = outputMimeType ?: Files.probeContentType(path) ?: "application/octet-stream"
        return FileReadResult(provider, attributes, mimeType)
    }

    fun list(
        directoryExpression: String,
        recursive: Boolean,
        matcher: LocalFileMatcher?,
        timeBetweenSizeCheck: Long?,
        timeBetweenSizeCheckUnit: TimeUnit,
        subset: LocalSubsetList?,
    ): List<FileMessage> {
        val selected = listAttributes(directoryExpression, recursive, matcher, subset)
        val messages = ArrayList<FileMessage>(selected.size)
        try {
            selected.forEach { attributes ->
                val provider =
                    if (attributes.regularFile) {
                        StreamingProviders.managed(
                            LocalFileCursorStreamProvider(
                                file = Path.of(attributes.path),
                                lock = false,
                                timeBetweenSizeCheck = timeBetweenSizeCheck,
                                timeBetweenSizeCheckUnit = timeBetweenSizeCheckUnit,
                                lockRegistry = locks,
                            ),
                        )
                    } else {
                        null
                    }
                messages += FileMessage(provider, attributes)
            }
            return messages
        } catch (failure: Exception) {
            messages.mapNotNull { it.payload }.forEach { it.releaseResources() }
            throw failure
        }
    }

    fun listAttributes(
        directoryExpression: String,
        recursive: Boolean,
        matcher: LocalFileMatcher?,
        subset: LocalSubsetList?,
    ): List<LocalFileAttributes> {
        val directory = requireDirectory(directoryExpression)
        val depth = if (recursive) Int.MAX_VALUE else 1
        val predicate = CompiledFileMatcher(matcher, clock.instant(), basePath)
        return Files.walk(directory, depth).use { paths ->
            paths.iterator().asSequence()
                .filter { it != directory }
                .map(::attributes)
                .filter(predicate::matches)
                .toList()
        }.let { applySubset(it, subset) }
    }

    fun write(
        pathExpression: String,
        content: Any,
        createParentDirectories: Boolean,
        lock: Boolean,
        mode: FileWriteMode,
        charset: java.nio.charset.Charset,
    ) {
        requireWritableContent(content)
        val path = resolve(pathExpression)
        ensureParentDirectory(path, createParentDirectories)
        val options =
            when (mode) {
                FileWriteMode.APPEND -> arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
                FileWriteMode.CREATE_NEW -> arrayOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                FileWriteMode.OVERWRITE -> arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            }

        FileChannel.open(path, *options).use { channel ->
            val fileLock =
                if (lock) {
                    try {
                        channel.tryLock()
                    } catch (failure: java.nio.channels.OverlappingFileLockException) {
                        throw FileConnectorException(FileErrorType.FILE_LOCK, "Could not lock file '$path'", failure)
                    } ?: throw FileConnectorException(FileErrorType.FILE_LOCK, "Could not lock file '$path'")
                } else {
                    null
                }
            if (fileLock != null) locks.acquire(path)
            try {
                if (mode == FileWriteMode.OVERWRITE) channel.truncate(0)
                writeContent(channel, content, charset)
            } finally {
                if (fileLock?.isValid == true) fileLock.release()
                if (fileLock != null) locks.release(path)
            }
        }
    }

    fun copy(
        sourceExpression: String,
        targetExpression: String,
        createParentDirectories: Boolean,
        overwrite: Boolean,
        renameTo: String?,
    ) {
        val source = existingPath(sourceExpression)
        ensureNotLocked(source)
        val destination = destination(source, targetExpression, createParentDirectories, renameTo)
        requireDestinationOutsideSource(source, destination)
        replaceOrCreate(destination, overwrite) {
            if (Files.isDirectory(source)) copyTree(source, destination) else copyFile(source, destination)
        }
    }

    fun move(
        sourceExpression: String,
        targetExpression: String,
        createParentDirectories: Boolean,
        overwrite: Boolean,
        renameTo: String?,
    ) {
        val source = existingPath(sourceExpression)
        ensureNotLocked(source)
        val destination = destination(source, targetExpression, createParentDirectories, renameTo)
        requireDestinationOutsideSource(source, destination)
        replaceOrCreate(destination, overwrite) { movePath(source, destination) }
    }

    fun delete(pathExpression: String) {
        val path = existingPath(pathExpression)
        ensureNotLocked(path)
        deleteTree(path)
    }

    fun rename(pathExpression: String, newName: String, overwrite: Boolean) {
        val source = existingPath(pathExpression)
        ensureNotLocked(source)
        val targetName = simpleName(newName)
        val destination = requireNotNull(source.parent) { "Cannot rename filesystem root '$source'" }.resolve(targetName)
        replaceOrCreate(destination, overwrite) { movePath(source, destination) }
    }

    fun createDirectory(directoryExpression: String) {
        val directory = resolve(directoryExpression)
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw FileConnectorException(FileErrorType.FILE_ALREADY_EXISTS, "Directory '$directory' already exists")
        }
        Files.createDirectories(directory)
    }

    fun resolve(pathExpression: String): Path =
        try {
            basePath.resolve(pathExpression).toAbsolutePath().normalize()
        } catch (failure: InvalidPathException) {
            throw FileConnectorException(FileErrorType.ILLEGAL_PATH, "Invalid path '$pathExpression'", failure)
        }

    fun requireDirectory(pathExpression: String): Path {
        val directory = existingPath(pathExpression)
        if (!Files.isDirectory(directory)) {
            throw FileConnectorException(
                FileErrorType.ILLEGAL_PATH,
                "Path '$directory' is not a directory",
            )
        }
        return directory
    }

    fun attributes(path: Path): LocalFileAttributes {
        val basic = Files.readAttributes(path, BasicFileAttributes::class.java)
        return LocalFileAttributes(
            path = path.toAbsolutePath().normalize().toString(),
            fileName = path.fileName?.toString().orEmpty(),
            lastModifiedTime = basic.lastModifiedTime().toInstant(),
            lastAccessTime = basic.lastAccessTime().toInstant(),
            creationTime = basic.creationTime().toInstant(),
            size = basic.size(),
            regularFile = basic.isRegularFile,
            directory = basic.isDirectory,
            symbolicLink = Files.isSymbolicLink(path),
        )
    }

    private fun existingPath(pathExpression: String): Path {
        val path = resolve(pathExpression)
        try {
            Files.readAttributes(path, BasicFileAttributes::class.java)
        } catch (failure: NoSuchFileException) {
            throw FileConnectorException(FileErrorType.ILLEGAL_PATH, "Path '$path' does not exist", failure)
        } catch (failure: AccessDeniedException) {
            throw FileConnectorException(FileErrorType.ACCESS_DENIED, "Access to path '$path' was denied", failure)
        }
        return path
    }

    private fun ensureParentDirectory(path: Path, createParentDirectories: Boolean) {
        val parent = path.parent ?: return
        if (Files.exists(parent)) {
            if (!Files.isDirectory(parent)) {
                throw FileConnectorException(
                    FileErrorType.ILLEGAL_PATH,
                    "Parent path '$parent' is not a directory",
                )
            }
        } else if (createParentDirectories) {
            Files.createDirectories(parent)
        } else {
            throw FileConnectorException(FileErrorType.ILLEGAL_PATH, "Parent directory '$parent' does not exist")
        }
    }

    private fun destination(
        source: Path,
        targetExpression: String,
        createParentDirectories: Boolean,
        renameTo: String?,
    ): Path {
        val targetDirectory = resolve(targetExpression)
        if (Files.exists(targetDirectory)) {
            if (!Files.isDirectory(targetDirectory)) {
                throw FileConnectorException(
                    FileErrorType.ILLEGAL_PATH,
                    "Target path '$targetDirectory' is not a directory",
                )
            }
        } else if (createParentDirectories) {
            Files.createDirectories(targetDirectory)
        } else {
            throw FileConnectorException(
                FileErrorType.ILLEGAL_PATH,
                "Target directory '$targetDirectory' does not exist",
            )
        }
        return targetDirectory.resolve(renameTo?.let(::simpleName) ?: source.fileName)
    }

    private fun simpleName(name: String): Path {
        val path =
            try {
                Path.of(name)
            } catch (failure: InvalidPathException) {
                throw FileConnectorException(FileErrorType.ILLEGAL_PATH, "Invalid file name '$name'", failure)
            }
        if (path.isAbsolute || path.nameCount != 1 || name == "." || name == "..") {
            throw FileConnectorException(FileErrorType.ILLEGAL_PATH, "File name '$name' must not contain a path")
        }
        return path
    }

    private fun requireDestinationOutsideSource(source: Path, destination: Path) {
        if (Files.isDirectory(source) && destination.startsWith(source)) {
            throw FileConnectorException(
                FileErrorType.ILLEGAL_PATH,
                "Destination '$destination' must not be inside source directory '$source'",
            )
        }
    }

    internal fun replaceOrCreate(destination: Path, overwrite: Boolean, transfer: () -> Unit) {
        if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            transfer()
            return
        }
        if (!overwrite) {
            throw FileConnectorException(
                FileErrorType.FILE_ALREADY_EXISTS,
                "Destination '$destination' already exists; enable overwrite to replace it",
            )
        }
        ensureNotLocked(destination)
        val backup = backupPath(destination)
        movePath(destination, backup)
        try {
            transfer()
        } catch (failure: Exception) {
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                runCatching { deleteTree(destination) }
            }
            if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                movePath(backup, destination)
            }
            throw failure
        }
        deleteTree(backup)
    }

    private fun backupPath(destination: Path): Path {
        val parent =
            destination.parent
                ?: throw FileConnectorException(
                    FileErrorType.ILLEGAL_PATH,
                    "Cannot replace filesystem root '$destination'",
                )
        val prefix = destination.fileName?.toString() ?: "destination"
        val preferred = parent.resolve("$prefix.muse-replacing")
        if (!Files.exists(preferred, LinkOption.NOFOLLOW_LINKS)) return preferred
        return parent.resolve("$prefix.muse-replacing-${System.nanoTime()}")
    }

    private fun movePath(source: Path, destination: Path) {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, destination)
        }
    }

    private fun ensureNotLocked(path: Path) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            if (locks.isLocked(path)) {
                throw FileConnectorException(FileErrorType.FILE_LOCK, "Cannot mutate locked file '$path'")
            }
            return
        }
        Files.walk(path).use { stream ->
            stream.filter { locks.isLocked(it) }.findFirst().ifPresent { locked ->
                throw FileConnectorException(FileErrorType.FILE_LOCK, "Cannot mutate locked file '$locked'")
            }
        }
    }

    private fun copyTree(source: Path, destination: Path) {
        Files.walkFileTree(
            source,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.createDirectories(destination.resolve(source.relativize(dir)))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    copyFile(file, destination.resolve(source.relativize(file)))
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun copyFile(source: Path, destination: Path) {
        Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES)
    }

    private fun deleteTree(path: Path) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(path)
            return
        }
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, failure: java.io.IOException?): FileVisitResult {
                    if (failure != null) throw failure
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun requireWritableContent(content: Any) {
        when (content) {
            is ByteArray, is ByteBuffer, is String, is MuseCursorStreamProvider, is InputStream -> Unit
            else ->
                throw FileConnectorException(
                    FileErrorType.ILLEGAL_CONTENT,
                    "File write content must be bytes, text, or a stream but was ${content::class.qualifiedName}",
                )
        }
    }

    private fun writeContent(channel: FileChannel, content: Any, charset: java.nio.charset.Charset) {
        when (content) {
            is ByteArray -> writeFully(channel, ByteBuffer.wrap(content))
            is ByteBuffer -> writeFully(channel, content)
            is String -> writeFully(channel, ByteBuffer.wrap(content.toByteArray(charset)))
            is MuseCursorStreamProvider -> content.openCursor().use { cursor -> cursor.copyTo(Channels.newOutputStream(channel)) }
            is InputStream -> content.use { input -> input.copyTo(Channels.newOutputStream(channel)) }
            else -> error("Unsupported file write content ${content::class.qualifiedName}")
        }
    }

    private fun writeFully(channel: FileChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    private fun applySubset(
        attributes: List<LocalFileAttributes>,
        subset: LocalSubsetList?,
    ): List<LocalFileAttributes> {
        if (subset == null) return attributes
        val comparator =
            when (subset.criteria) {
                FileListSortCriteria.ALPHABETICALLY -> compareBy<LocalFileAttributes> { it.fileName }.thenBy { it.path }
                FileListSortCriteria.DATE_MODIFIED -> compareBy<LocalFileAttributes> { it.lastModifiedTime }.thenBy { it.path }
                FileListSortCriteria.DATE_CREATED -> compareBy<LocalFileAttributes> { it.creationTime }.thenBy { it.path }
                FileListSortCriteria.SIZE -> compareBy<LocalFileAttributes> { it.size }.thenBy { it.path }
                FileListSortCriteria.PATH -> compareBy { it.path }
            }.let { if (subset.order == SortOrder.DESCENDING) it.reversed() else it }
        val sorted = attributes.sortedWith(comparator).drop(subset.offset)
        return if (subset.limit == 0) sorted else sorted.take(subset.limit)
    }

    private class CompiledFileMatcher(
        private val matcher: LocalFileMatcher?,
        private val now: Instant,
        private val basePath: Path,
    ) {
        private val filenameMatcher = matcher?.filenamePattern?.let(::compileGlob)
        private val pathMatcher = matcher?.pathPattern?.let(::compileGlob)

        fun matches(attributes: LocalFileAttributes): Boolean {
            val matcher = matcher ?: return true
            return boundary(attributes.creationTime, matcher.createdSince, matcher.createdUntil) &&
                boundary(attributes.lastModifiedTime, matcher.updatedSince, matcher.updatedUntil) &&
                boundary(attributes.lastAccessTime, matcher.accessedSince, matcher.accessedUntil) &&
                matcher.notUpdatedInTheLast?.let { attributes.lastModifiedTime <= now.minus(it) } != false &&
                matcher.updatedInTheLast?.let { attributes.lastModifiedTime >= now.minus(it) } != false &&
                filenameMatcher?.matches(Path.of(attributes.fileName)) != false &&
                pathMatcher?.matches(relativeToBase(Path.of(attributes.path))) != false &&
                typeMatches(attributes.directory, matcher.directories) &&
                typeMatches(attributes.regularFile, matcher.regularFiles) &&
                typeMatches(attributes.symbolicLink, matcher.symbolicLinks) &&
                matcher.minSize?.let { attributes.size >= it } != false &&
                matcher.maxSize?.let { attributes.size <= it } != false
        }

        private fun relativeToBase(path: Path): Path {
            val normalized = path.toAbsolutePath().normalize()
            return if (normalized.startsWith(basePath)) basePath.relativize(normalized) else normalized
        }

        private fun boundary(value: Instant, since: Instant?, until: Instant?): Boolean =
            (since == null || value >= since) && (until == null || value <= until)

        private fun typeMatches(actual: Boolean, requirement: FileTypeRequirement): Boolean =
            when (requirement) {
                FileTypeRequirement.REQUIRE -> actual
                FileTypeRequirement.INCLUDE -> true
                FileTypeRequirement.EXCLUDE -> !actual
            }

        private fun compileGlob(pattern: String) =
            try {
                FileSystems.getDefault().getPathMatcher("glob:$pattern")
            } catch (failure: RuntimeException) {
                throw FileConnectorException(FileErrorType.ILLEGAL_PATH, "Invalid glob pattern '$pattern'", failure)
            }
    }

    private companion object {
        fun resolveBasePath(configured: String?): Path {
            val value =
                configured ?: System.getProperty("user.home")
                    ?: throw FileConnectorException(
                        FileErrorType.ILLEGAL_PATH,
                        "File connector requires workingDirectory when user.home is unavailable",
                    )
            return try {
                Path.of(value).toAbsolutePath().normalize()
            } catch (failure: InvalidPathException) {
                throw FileConnectorException(FileErrorType.ILLEGAL_PATH, "Invalid working directory '$value'", failure)
            }
        }
    }
}
