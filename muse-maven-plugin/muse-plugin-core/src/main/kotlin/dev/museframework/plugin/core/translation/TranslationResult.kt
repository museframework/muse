package dev.museframework.plugin.core.translation

import dev.museframework.plugin.core.codegen.GeneratedFile
import java.io.File

/**
 * Outcome of translating a single MuleSoft XML file.
 *
 * @property sourceFile     The original XML file.
 * @property generatedFiles Kotlin source files to write. Normally one per XML document
 *                          (a @MuseConfig class), but may be more for complex documents.
 * @property warnings       Non-fatal issues (e.g. unmapped elements skipped in lenient mode).
 */
data class TranslationResult(
    val sourceFile: File,
    val generatedFiles: List<GeneratedFile>,
    val warnings: List<String> = emptyList()
)
