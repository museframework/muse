package dev.museframework.file.api.model

import dev.museframework.engine.api.streaming.MuseCursorStreamProvider
import java.time.Duration
import java.time.Instant

enum class FileWriteMode {
    OVERWRITE,
    APPEND,
    CREATE_NEW,
}

enum class FileTypeRequirement {
    REQUIRE,
    INCLUDE,
    EXCLUDE,
}

enum class FileListSortCriteria {
    ALPHABETICALLY,
    DATE_MODIFIED,
    DATE_CREATED,
    SIZE,
    PATH,
}

enum class SortOrder {
    ASCENDING,
    DESCENDING,
}

enum class WatermarkMode {
    DISABLED,
    MODIFIED_TIMESTAMP,
    CREATED_TIMESTAMP,
}

data class LocalFileMatcher(
    val createdSince: Instant? = null,
    val createdUntil: Instant? = null,
    val updatedSince: Instant? = null,
    val updatedUntil: Instant? = null,
    val accessedSince: Instant? = null,
    val accessedUntil: Instant? = null,
    val notUpdatedInTheLast: Duration? = null,
    val updatedInTheLast: Duration? = null,
    val filenamePattern: String? = null,
    val pathPattern: String? = null,
    val directories: FileTypeRequirement = FileTypeRequirement.INCLUDE,
    val regularFiles: FileTypeRequirement = FileTypeRequirement.INCLUDE,
    val symbolicLinks: FileTypeRequirement = FileTypeRequirement.INCLUDE,
    val minSize: Long? = null,
    val maxSize: Long? = null,
) {
    init {
        require(notUpdatedInTheLast == null || !notUpdatedInTheLast.isNegative) {
            "notUpdatedInTheLast must not be negative"
        }
        require(updatedInTheLast == null || !updatedInTheLast.isNegative) {
            "updatedInTheLast must not be negative"
        }
        require(minSize == null || minSize > 0) { "minSize must be greater than zero" }
        require(maxSize == null || maxSize > 0) { "maxSize must be greater than zero" }
        require(minSize == null || maxSize == null || minSize <= maxSize) {
            "minSize must not exceed maxSize"
        }
    }
}

data class LocalSubsetList(
    val limit: Int = 0,
    val offset: Int = 0,
    val criteria: FileListSortCriteria = FileListSortCriteria.DATE_MODIFIED,
    val order: SortOrder = SortOrder.DESCENDING,
) {
    init {
        require(limit >= 0) { "limit must be greater than or equal to zero" }
        require(offset >= 0) { "offset must be greater than or equal to zero" }
    }
}

data class FileMessage(
    val payload: MuseCursorStreamProvider?,
    val attributes: LocalFileAttributes,
)
