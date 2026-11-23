package dev.museframework.babelfish.mappers.file

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.buildCodeBlock
import dev.museframework.babelfish.api.ConfigMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ConfigCodeEmitter
import dev.museframework.babelfish.emitter.kotlinNumericLiteral
import dev.museframework.babelfish.emitter.kotlinStringLiteral
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawConfig
import dev.museframework.babelfish.model.RawElement
import dev.museframework.babelfish.model.SourceLocation
import dev.museframework.file.api.config.FileMatcherConfig
import dev.museframework.file.api.model.FileListSortCriteria
import dev.museframework.file.api.model.FileTypeRequirement
import dev.museframework.file.api.model.LocalFileMatcher
import dev.museframework.file.api.model.LocalSubsetList
import dev.museframework.file.api.model.SortOrder
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID
import java.util.concurrent.TimeUnit

object FileMatcherConfigMapper : ConfigMapper {
    override fun map(
        config: RawConfig,
        ctx: MappingContext,
    ): FileMatcherConfig {
        val name = config.attrs["name"] ?: config.id
        return FileMatcherConfig(
            name = name,
            docName = config.attrs["doc:name"] ?: name,
            docId = config.attrs["doc:id"] ?: UUID.randomUUID().toString(),
            matcher = localFileMatcher(config.attrs),
        )
    }
}

object FileMatcherConfigCodeEmitter : ConfigCodeEmitter<FileMatcherConfig> {
    override fun emit(
        config: FileMatcherConfig,
        ctx: CodegenContext,
    ): CodeBlock = buildCodeBlock {
        beginControlFlow("%M", fileDsl("fileMatcher"))
        addString("name", config.name)
        if (config.docName.isNotBlank() && config.docName != config.name) addString("docName", config.docName)
        addString("docId", config.docId)
        addMatcher(config.matcher)
        endControlFlow()
    }
}

internal fun resolveFileMatcher(
    element: RawElement,
    ctx: MappingContext,
): LocalFileMatcher? {
    val referenced =
        element.attrs["matcher"]?.let { name ->
            val config =
                ctx.configs[name] as? FileMatcherConfig
                    ?: throw IllegalArgumentException("file matcher '$name' was not found")
            config.matcher
        }
    val inline = element.children.find { it.tag == "matcher" }?.let { localFileMatcher(it.attrs) }
    return mergeMatchers(referenced, inline)
}

internal fun mapSubset(element: RawElement): LocalSubsetList? {
    val subset = element.children.find { it.tag == "subset" } ?: return null
    return LocalSubsetList(
        limit = subset.optionalInt("limit") ?: 0,
        offset = subset.optionalInt("offset") ?: 0,
        criteria = subset.optionalEnum("criteria") ?: FileListSortCriteria.DATE_MODIFIED,
        order = subset.optionalEnum("order") ?: SortOrder.DESCENDING,
    )
}

internal fun localFileMatcher(attrs: Map<String, String>): LocalFileMatcher {
    val element =
        RawElement(
            namespace = "file",
            tag = "matcher",
            attrs = attrs,
            children = emptyList(),
            location = SourceLocation(0, 0),
        )
    val timeUnit = element.attrs["timeUnit"]?.toTimeUnit() ?: TimeUnit.MILLISECONDS
    return LocalFileMatcher(
        createdSince = element.optionalInstant("createdSince"),
        createdUntil = element.optionalInstant("createdUntil"),
        updatedSince = element.optionalInstant("updatedSince"),
        updatedUntil = element.optionalInstant("updatedUntil"),
        accessedSince = element.optionalInstant("accessedSince"),
        accessedUntil = element.optionalInstant("accessedUntil"),
        notUpdatedInTheLast = element.optionalDuration("notUpdatedInTheLast", timeUnit),
        updatedInTheLast = element.optionalDuration("updatedInTheLast", timeUnit),
        filenamePattern = element.attrs["filenamePattern"],
        pathPattern = element.attrs["pathPattern"] ?: element.attrs["path-pattern"],
        directories = element.policy("directories"),
        regularFiles = element.policy("regularFiles"),
        symbolicLinks = element.policy("symLinks", "symbolicLinks"),
        minSize = element.optionalLong("minSize"),
        maxSize = element.optionalLong("maxSize"),
    )
}

internal fun mergeMatchers(
    primary: LocalFileMatcher?,
    extra: LocalFileMatcher?,
): LocalFileMatcher? {
    if (primary == null) return extra
    if (extra == null) return primary
    return LocalFileMatcher(
        createdSince = mergeField("createdSince", primary.createdSince, extra.createdSince),
        createdUntil = mergeField("createdUntil", primary.createdUntil, extra.createdUntil),
        updatedSince = mergeField("updatedSince", primary.updatedSince, extra.updatedSince),
        updatedUntil = mergeField("updatedUntil", primary.updatedUntil, extra.updatedUntil),
        accessedSince = mergeField("accessedSince", primary.accessedSince, extra.accessedSince),
        accessedUntil = mergeField("accessedUntil", primary.accessedUntil, extra.accessedUntil),
        notUpdatedInTheLast = mergeField("notUpdatedInTheLast", primary.notUpdatedInTheLast, extra.notUpdatedInTheLast),
        updatedInTheLast = mergeField("updatedInTheLast", primary.updatedInTheLast, extra.updatedInTheLast),
        filenamePattern = mergeField("filenamePattern", primary.filenamePattern, extra.filenamePattern),
        pathPattern = mergeField("pathPattern", primary.pathPattern, extra.pathPattern),
        directories = mergePolicy("directories", primary.directories, extra.directories),
        regularFiles = mergePolicy("regularFiles", primary.regularFiles, extra.regularFiles),
        symbolicLinks = mergePolicy("symbolicLinks", primary.symbolicLinks, extra.symbolicLinks),
        minSize = mergeField("minSize", primary.minSize, extra.minSize),
        maxSize = mergeField("maxSize", primary.maxSize, extra.maxSize),
    )
}

internal fun CodeBlock.Builder.addMatcher(matcher: LocalFileMatcher?) {
    if (matcher == null) return
    add("matcher = %T(\n", LocalFileMatcher::class)
    indent()
    fun instant(name: String, value: Instant?) {
        value?.let { add("%N = %T.parse(%S),\n", name, Instant::class, it.toString()) }
    }
    fun duration(name: String, value: Duration?) {
        value?.let { add("%N = %T.ofMillis(%L),\n", name, Duration::class, kotlinNumericLiteral(it.toMillis())) }
    }
    fun policy(name: String, value: FileTypeRequirement) {
        if (value != FileTypeRequirement.INCLUDE) add("%N = %T.%N,\n", name, FileTypeRequirement::class, value.name)
    }

    instant("createdSince", matcher.createdSince)
    instant("createdUntil", matcher.createdUntil)
    instant("updatedSince", matcher.updatedSince)
    instant("updatedUntil", matcher.updatedUntil)
    instant("accessedSince", matcher.accessedSince)
    instant("accessedUntil", matcher.accessedUntil)
    duration("notUpdatedInTheLast", matcher.notUpdatedInTheLast)
    duration("updatedInTheLast", matcher.updatedInTheLast)
    matcher.filenamePattern?.let { add("filenamePattern = %L,\n", kotlinStringLiteral(it)) }
    matcher.pathPattern?.let { add("pathPattern = %L,\n", kotlinStringLiteral(it)) }
    policy("directories", matcher.directories)
    policy("regularFiles", matcher.regularFiles)
    policy("symbolicLinks", matcher.symbolicLinks)
    matcher.minSize?.let { add("minSize = %L,\n", kotlinNumericLiteral(it)) }
    matcher.maxSize?.let { add("maxSize = %L,\n", kotlinNumericLiteral(it)) }
    unindent()
    add(")\n")
}

internal fun CodeBlock.Builder.addSubset(subset: LocalSubsetList?) {
    if (subset == null) return
    add("subset = %T(\n", LocalSubsetList::class)
    indent()
    if (subset.limit != 0) add("limit = %L,\n", kotlinNumericLiteral(subset.limit))
    if (subset.offset != 0) add("offset = %L,\n", kotlinNumericLiteral(subset.offset))
    if (subset.criteria != FileListSortCriteria.DATE_MODIFIED) {
        add("criteria = %T.%N,\n", FileListSortCriteria::class, subset.criteria.name)
    }
    if (subset.order != SortOrder.DESCENDING) add("order = %T.%N,\n", SortOrder::class, subset.order.name)
    unindent()
    add(")\n")
}

private fun <T> mergeField(name: String, left: T?, right: T?): T? =
    when {
        left == null -> right
        right == null -> left
        left == right -> left
        else -> throw IllegalArgumentException("file matcher field '$name' is set to different values")
    }

private fun mergePolicy(name: String, left: FileTypeRequirement, right: FileTypeRequirement): FileTypeRequirement =
    when {
        left == FileTypeRequirement.INCLUDE -> right
        right == FileTypeRequirement.INCLUDE -> left
        left == right -> left
        else -> throw IllegalArgumentException("file matcher field '$name' is set to different values")
    }

private fun RawElement.optionalLong(name: String): Long? {
    val text = attrs[name] ?: return null
    return text.toLongOrNull()
        ?: throw IllegalArgumentException("file matcher $name must be an integer but was '$text'")
}

private fun RawElement.optionalInt(name: String): Int? {
    val text = attrs[name] ?: return null
    return text.toIntOrNull()
        ?: throw IllegalArgumentException("file subset $name must be an integer but was '$text'")
}

private inline fun <reified T : Enum<T>> RawElement.optionalEnum(name: String): T? {
    val text = attrs[name] ?: return null
    return try {
        text.toFileEnum()
    } catch (failure: IllegalArgumentException) {
        throw IllegalArgumentException("file subset $name has unsupported value '$text'", failure)
    }
}

private fun RawElement.optionalInstant(name: String): Instant? {
    val text = attrs[name] ?: return null
    return try {
        val parsed = DateTimeFormatter.ISO_DATE_TIME.parseBest(text.trim(), OffsetDateTime::from, LocalDateTime::from)
        when (parsed) {
            is OffsetDateTime -> parsed.toInstant()
            is LocalDateTime -> parsed.atZone(ZoneId.systemDefault()).toInstant()
            else -> throw DateTimeParseException("Unsupported file matcher timestamp", text, 0)
        }
    } catch (failure: DateTimeParseException) {
        throw IllegalArgumentException("file matcher $name must be an ISO-8601 date-time but was '$text'", failure)
    }
}

private fun RawElement.optionalDuration(name: String, unit: TimeUnit): Duration? {
    val amount = optionalLong(name) ?: return null
    return Duration.ofMillis(unit.toMillis(amount))
}

private fun RawElement.policy(primary: String, alias: String? = null): FileTypeRequirement {
    val text = attrs[primary] ?: alias?.let { attrs[it] } ?: return FileTypeRequirement.INCLUDE
    return try {
        text.toFileEnum()
    } catch (failure: IllegalArgumentException) {
        throw IllegalArgumentException(
            "file matcher $primary must be REQUIRE, INCLUDE, or EXCLUDE but was '$text'",
            failure,
        )
    }
}
