package dev.museframework.plugin.core.translation

import dev.museframework.babelfish.MuseBabelfish
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.babelfish.emitter.ConfigurationClassSpec
import dev.museframework.babelfish.exception.BabelfishParseException
import dev.museframework.babelfish.exception.BabelfishTranslationException
import dev.museframework.babelfish.exception.UnmappedElementException
import dev.museframework.plugin.core.codegen.GeneratedFile
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path

class TranslatorFacade(
    private val registry: TranslatorRegistry = TranslatorRegistry.load(),
) {
    private val log = LoggerFactory.getLogger(TranslatorFacade::class.java)

    fun translateAll(
        sourceDirectory: File,
        packageName: String,
        configurationClassSpec: ConfigurationClassSpec = ConfigurationClassSpec.Plain,
        nativeBuild: Boolean = false,
        lenient: Boolean = false,
    ): List<TranslationResult> {
        if (!sourceDirectory.exists()) {
            log.warn("Source directory does not exist: $sourceDirectory — skipping")
            return emptyList()
        }

        val xmlFiles =
            sourceDirectory
                .walkTopDown()
                .filter { it.isFile && it.extension.equals("xml", ignoreCase = true) }
                .toList()

        if (xmlFiles.isEmpty()) {
            log.info("No XML files found in $sourceDirectory")
            return emptyList()
        }

        log.info("Translating ${xmlFiles.size} XML file(s) from $sourceDirectory")
        return xmlFiles.map { translateFile(it, packageName, configurationClassSpec, nativeBuild, lenient) }
    }

    private fun translateFile(
        file: File,
        packageName: String,
        configurationClassSpec: ConfigurationClassSpec,
        nativeBuild: Boolean,
        lenient: Boolean,
    ): TranslationResult =
        try {
            val babelfishResult =
                MuseBabelfish.translateXmlToMuseKotlinDsl(
                    file.toPath(),
                    registry.createRegistry(),
                    lenient,
                )

            val emitter = KotlinCodeEmitter()
            registry.registerEmitters(emitter, nativeBuild)

            val kotlinCode =
                emitter.emit(
                    babelfishResult,
                    packageName,
                    nativeBuild,
                    configurationClassSpec,
                )

            val className = deriveClassName(file)
            val relativePath = "${packageName.replace('.', '/')}/$className.kt"

            TranslationResult(
                sourceFile = file,
                generatedFiles = listOf(GeneratedFile(relativePath, kotlinCode)),
                warnings = emptyList(),
            )
        } catch (e: UnmappedElementException) {
            handleUnmappedElement(file, e, lenient)
        } catch (e: BabelfishTranslationException) {
            handleBabelfishTranslationException(file, e, lenient)
        } catch (e: BabelfishParseException) {
            throw MuseTranslationException(
                "Failed to parse '${file.absolutePath}': ${e.message}",
                e,
            )
        }

    private fun handleUnmappedElement(
        file: File,
        e: UnmappedElementException,
        lenient: Boolean,
    ): TranslationResult = handleMissingMapperDiagnostics(file, listOf(e), lenient, e)

    private fun handleBabelfishTranslationException(
        file: File,
        e: BabelfishTranslationException,
        lenient: Boolean,
    ): TranslationResult = handleMissingMapperDiagnostics(file, e.errors, lenient, e)

    private fun handleMissingMapperDiagnostics(
        file: File,
        errors: List<UnmappedElementException>,
        lenient: Boolean,
        cause: Throwable,
    ): TranslationResult {
        val message = formatMissingMapperDiagnostic(file, errors, lenient)
        return if (lenient) {
            TranslationResult(
                sourceFile = file,
                generatedFiles = emptyList(),
                warnings = listOf(message),
            )
        } else {
            throw MissingMapperTranslationException(message, cause)
        }
    }

    internal fun deriveClassName(file: File): String =
        file.nameWithoutExtension
            .split("-", "_", " ")
            .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) } + "Config"
}

internal fun formatMissingMapperDiagnostic(
    file: File,
    errors: List<UnmappedElementException>,
    lenient: Boolean,
): String {
    require(errors.isNotEmpty()) { "At least one unmapped element is required" }
    val occurrences =
        errors
            .map(UnmappedElementException::toMissingMapperOccurrence)
            .distinct()
            .sortedWith(
                compareBy(
                    MissingMapperOccurrence::sourcePath,
                    MissingMapperOccurrence::line,
                    MissingMapperOccurrence::column,
                    MissingMapperOccurrence::namespace,
                    MissingMapperOccurrence::tag,
                ),
            )
    val occurrenceLabel =
        if (occurrences.size == 1) {
            "1 distinct unmapped occurrence"
        } else {
            "${occurrences.size} distinct unmapped occurrences"
        }
    val header =
        if (lenient) {
            "Skipped generation for '${file.name}': $occurrenceLabel"
        } else {
            "Failed to translate '${file.name}': $occurrenceLabel"
        }

    return buildString {
        appendLine(header)
        occurrences.forEachIndexed { index, occurrence ->
            appendLine(
                "${index + 1}. no mapper registered for '${occurrence.qualifiedTag}' " +
                    "at line ${occurrence.line}, col ${occurrence.column}",
            )
        }
        append(MISSING_MAPPER_GUIDANCE)
    }
}

private data class MissingMapperOccurrence(
    val sourcePath: String,
    val line: Int,
    val column: Int,
    val namespace: String,
    val tag: String,
) {
    val qualifiedTag: String = if (namespace.isEmpty()) tag else "$namespace:$tag"
}

private fun UnmappedElementException.toMissingMapperOccurrence() =
    MissingMapperOccurrence(
        sourcePath = sourceFile.normalizedSourcePath(),
        line = location.line,
        column = location.col,
        namespace = namespace,
        tag = tag,
    )

private fun Path.normalizedSourcePath(): String =
    toAbsolutePath()
        .normalize()
        .toString()
        .replace(File.separatorChar, '/')

private const val MISSING_MAPPER_GUIDANCE =
    "Add the appropriate mapper artifact under the Maven plugin's <dependencies> element; " +
        "an ordinary project dependency is not visible to the plugin realm."

open class MuseTranslationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class MissingMapperTranslationException(
    message: String,
    cause: Throwable,
) : MuseTranslationException(message, cause)
