package dev.museframework.babelfish.mappers.sftp

import com.squareup.kotlinpoet.CodeBlock
import dev.museframework.babelfish.emitter.kotlinStringLiteral
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

internal fun CodeBlock.Builder.addMatcher(matcher: SftpFileMatcher, default: SftpFileMatcher) {
    if (matcher == default) return
    add("matcher = %T(\n", SftpFileMatcher::class)
    indent()
    matcher.filenamePattern?.let { add("filenamePattern = %L,\n", kotlinStringLiteral(it)) }
    matcher.pathPattern?.let { add("pathPattern = %L,\n", kotlinStringLiteral(it)) }
    if (matcher.fileType != SftpFileType.ANY) add("fileType = %T.%N,\n", SftpFileType::class, matcher.fileType.name)
    matcher.minSize?.let { add("minSize = %L,\n", it) }
    matcher.maxSize?.let { add("maxSize = %L,\n", it) }
    if (!matcher.caseSensitive) add("caseSensitive = false,\n")
    unindent()
    add(")\n")
}
