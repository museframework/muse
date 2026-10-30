package dev.museframework.file.api.model

import java.time.Instant

data class LocalFileAttributes(
    val path: String,
    val fileName: String,
    val lastModifiedTime: Instant,
    val lastAccessTime: Instant,
    val creationTime: Instant,
    val size: Long,
    val regularFile: Boolean,
    val directory: Boolean,
    val symbolicLink: Boolean,
)
