package dev.museframework.plugin.core.check

import dev.museframework.babelfish.model.SourceLocation
import java.nio.file.Path

data class CheckResult(
    val namespace: String,
    val tag: String,
    val type: ComponentType,
    val implemented: Boolean,
    val location: SourceLocation,
    val depth: Int,
)

enum class ComponentType { SOURCE, PROCESSOR, CONFIG }

data class CheckReport(
    val sourceFile: Path,
    val results: List<CheckResult>,
) {
    val unmappedCount: Int get() = results.count { !it.implemented }
    val totalCount: Int get() = results.size
}

data class CompatibilityReport(
    val fileReports: List<CheckReport>,
    val namespacesFound: Set<String>,
) {
    val totalUnmapped: Int get() = fileReports.sumOf { it.unmappedCount }
    val totalComponents: Int get() = fileReports.sumOf { it.totalCount }

    fun hasBlockingIssues(): Boolean = totalUnmapped > 0
}
