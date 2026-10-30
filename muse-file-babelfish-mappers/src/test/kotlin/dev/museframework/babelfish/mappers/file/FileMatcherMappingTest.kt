package dev.museframework.babelfish.mappers.file

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawConfig
import dev.museframework.babelfish.model.RawElement
import dev.museframework.babelfish.model.SourceLocation
import dev.museframework.file.api.config.FileMatcherConfig
import dev.museframework.file.api.model.FileListSortCriteria
import dev.museframework.file.api.model.FileTypeRequirement
import dev.museframework.file.api.model.SortOrder
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileMatcherMappingTest {
    private val location = SourceLocation(4, 2)
    private val ctx = MappingContext(BabelfishRegistry(), Path.of("test.xml"))

    @Test
    fun `inline matcher and subset are mapped and emitted`() {
        val list =
            FileListMapper.map(
                listElement(
                    children =
                        listOf(
                            matcherElement(
                                mapOf(
                                    "filenamePattern" to "*.txt",
                                    "path-pattern" to "inbox/*",
                                    "symLinks" to "EXCLUDE",
                                    "updatedInTheLast" to "1",
                                    "timeUnit" to "SECONDS",
                                    "createdSince" to "2019-06-03T13:21:58",
                                    "maxSize" to "100",
                                ),
                            ),
                            RawElement(
                                namespace = "file",
                                tag = "subset",
                                attrs =
                                    mapOf(
                                        "criteria" to "DATE_CREATED",
                                        "limit" to "5",
                                        "offset" to "6",
                                        "order" to "ASCENDING",
                                    ),
                                children = emptyList(),
                                location = location,
                            ),
                        ),
                ),
                ctx,
            )

        assertEquals("*.txt", list.matcher?.filenamePattern)
        assertEquals("inbox/*", list.matcher?.pathPattern)
        assertEquals(FileTypeRequirement.EXCLUDE, list.matcher?.symbolicLinks)
        assertEquals(Duration.ofSeconds(1), list.matcher?.updatedInTheLast)
        assertEquals(
            LocalDateTime.parse("2019-06-03T13:21:58").atZone(ZoneId.systemDefault()).toInstant(),
            list.matcher?.createdSince,
        )
        assertEquals(100L, list.matcher?.maxSize)
        assertEquals(5, list.subset?.limit)
        assertEquals(6, list.subset?.offset)
        assertEquals(FileListSortCriteria.DATE_CREATED, list.subset?.criteria)
        assertEquals(SortOrder.ASCENDING, list.subset?.order)

        val code = FileListCodeEmitter.emit(list, CodegenContext("dev.example"))
        assertContains(code, "filenamePattern = \"*.txt\"")
        assertContains(code, "pathPattern = \"inbox/*\"")
        assertContains(code, "symbolicLinks = FileTypeRequirement.EXCLUDE")
        assertContains(code, "updatedInTheLast = Duration.ofMillis(1000)")
        assertContains(code, "criteria = FileListSortCriteria.DATE_CREATED")
        assertContains(code, "order = SortOrder.ASCENDING")
    }

    @Test
    fun `named matcher is resolved and merged with a disjoint inline matcher`() {
        ctx.configs["smallFileMatcher"] =
            FileMatcherConfig(
                name = "smallFileMatcher",
                matcher =
                    localFileMatcher(
                        mapOf(
                            "maxSize" to "100",
                            "updatedSince" to "2019-06-03T13:21:58Z",
                        ),
                    ),
            )

        val list =
            FileListMapper.map(
                listElement(
                    attrs = mapOf("config-ref" to "File_Config", "directoryPath" to "inbox", "matcher" to "smallFileMatcher"),
                    children = listOf(matcherElement(mapOf("filenamePattern" to "*.txt"))),
                ),
                ctx,
            )

        assertEquals(100L, list.matcher?.maxSize)
        assertEquals("*.txt", list.matcher?.filenamePattern)
        assertEquals(Instant.parse("2019-06-03T13:21:58Z"), list.matcher?.updatedSince)
    }

    @Test
    fun `missing named matcher fails translation`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                FileListMapper.map(
                    listElement(attrs = mapOf("config-ref" to "File_Config", "directoryPath" to "inbox", "matcher" to "absent")),
                    ctx,
                )
            }
        assertTrue(failure.message.orEmpty().contains("absent"))
    }

    @Test
    fun `conflicting matcher fields fail translation`() {
        ctx.configs["smallFileMatcher"] =
            FileMatcherConfig(name = "smallFileMatcher", matcher = localFileMatcher(mapOf("filenamePattern" to "*.txt")))

        assertFailsWith<IllegalArgumentException> {
            FileListMapper.map(
                listElement(
                    attrs = mapOf("config-ref" to "File_Config", "directoryPath" to "inbox", "matcher" to "smallFileMatcher"),
                    children = listOf(matcherElement(mapOf("filenamePattern" to "a*.txt"))),
                ),
                ctx,
            )
        }
    }

    @Test
    fun `top level matcher keeps its name`() {
        val config =
            FileMatcherConfigMapper.map(
                RawConfig(
                    id = "smallFileMatcher",
                    namespace = "file",
                    tag = "matcher",
                    attrs = mapOf("name" to "smallFileMatcher", "minSize" to "2"),
                    children = emptyList(),
                    location = location,
                ),
                ctx,
            )

        assertEquals("smallFileMatcher", config.name)
        assertEquals(2L, config.matcher.minSize)
        assertNull(config.matcher.filenamePattern)
    }

    private fun listElement(
        attrs: Map<String, String> = mapOf("config-ref" to "File_Config", "directoryPath" to "inbox"),
        children: List<RawElement> = emptyList(),
    ): RawElement =
        RawElement(
            namespace = "file",
            tag = "list",
            attrs = attrs,
            children = children,
            location = location,
        )

    private fun matcherElement(attrs: Map<String, String>): RawElement =
        RawElement(
            namespace = "file",
            tag = "matcher",
            attrs = attrs,
            children = emptyList(),
            location = location,
        )
}
