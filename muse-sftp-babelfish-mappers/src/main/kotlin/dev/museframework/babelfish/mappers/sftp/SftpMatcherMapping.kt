package dev.museframework.babelfish.mappers.sftp

import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.sftp.api.model.SftpFileMatcher
import dev.museframework.sftp.api.model.SftpFileType

internal fun mapSftpMatcher(
    element: RawElement,
    @Suppress("UNUSED_PARAMETER") ctx: MappingContext,
    defaultFileType: SftpFileType = SftpFileType.ANY,
): SftpFileMatcher {
    val nested = element.children.find { it.tag == "matcher" }
    val attrs = nested?.attrs ?: emptyMap()
    val directories = attrs["directories"]
    val regularFiles = attrs["regularFiles"]
    val symLinks = attrs["symLinks"]
    val fileType =
        when {
            attrs["fileType"] != null -> attrs.getValue("fileType").toSftpEnum<SftpFileType>()
            directories.equals("REQUIRE", ignoreCase = true) -> SftpFileType.DIRECTORY
            regularFiles.equals("REQUIRE", ignoreCase = true) -> SftpFileType.REGULAR_FILE
            symLinks.equals("REQUIRE", ignoreCase = true) -> SftpFileType.SYMBOLIC_LINK
            // EXCLUDE directories + EXCLUDE symlinks is the common "files only" matcher shape.
            directories.equals("EXCLUDE", ignoreCase = true) &&
                symLinks.equals("EXCLUDE", ignoreCase = true) -> SftpFileType.REGULAR_FILE
            else -> defaultFileType
        }
    return SftpFileMatcher(
        filenamePattern = attrs["filenamePattern"] ?: attrs["filename-pattern"],
        pathPattern = attrs["pathPattern"] ?: attrs["path-pattern"],
        fileType = fileType,
        minSize = attrs["minSize"]?.toLongOrNull(),
        maxSize = attrs["maxSize"]?.toLongOrNull(),
        caseSensitive = attrs["caseSensitive"]?.toBooleanStrictOrNull() ?: true,
    )
}

internal fun StringBuilder.appendSftpMatcherAssignment(
    property: String,
    matcher: SftpFileMatcher,
    default: SftpFileMatcher,
    ctx: CodegenContext,
    indent: String = "    ",
) {
    if (matcher == default) return
    ctx.addImport("dev.museframework.sftp.api.model.SftpFileMatcher")
    ctx.addImport("dev.museframework.sftp.api.model.SftpFileType")
    appendLine("$indent$property = SftpFileMatcher(")
    matcher.filenamePattern?.let {
        appendLine("$indent    filenamePattern = \"${ctx.escapeKotlinString(it)}\",")
    }
    matcher.pathPattern?.let {
        appendLine("$indent    pathPattern = \"${ctx.escapeKotlinString(it)}\",")
    }
    if (matcher.fileType != SftpFileType.ANY) {
        appendLine("$indent    fileType = SftpFileType.${matcher.fileType},")
    }
    matcher.minSize?.let { appendLine("$indent    minSize = $it,") }
    matcher.maxSize?.let { appendLine("$indent    maxSize = $it,") }
    if (!matcher.caseSensitive) {
        appendLine("$indent    caseSensitive = false,")
    }
    appendLine("$indent)")
}
