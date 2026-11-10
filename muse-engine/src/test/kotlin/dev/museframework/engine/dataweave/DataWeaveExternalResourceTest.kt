package dev.museframework.engine.dataweave

import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataWeaveExternalResourceTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `external file shorter than cwd evaluates in an isolated JVM`() = runProbe("short")

    @Test
    fun `external file longer than cwd evaluates in an isolated JVM`() = runProbe("long")

    @Test
    fun `cwd descendant file evaluates in an isolated JVM`() = runProbe("descendant")

    @Test
    fun `external script preserves classpath imports`() = runProbe("short", "import")

    @Test
    fun `short external diagnostic preserves source name and line`() = runProbe("short", "diagnostic")

    @Test
    fun `long external diagnostic preserves source name and line`() = runProbe("long", "diagnostic")

    @Test
    fun `same named external files do not share compiled scripts`() = runProbe("short", "distinct")

    @Test
    fun `external path with spaces and Unicode preserves file access`() = runProbe("short", "unicode")

    @Test
    fun `relative filesystem path preserves resource evaluation`() = runProbe("descendant", "relative")

    @Test
    fun `symlink parent traversal reads the physical target`() = runProbe("descendant", "symlink")

    private fun runProbe(layout: String, scenario: String = "value") {
        val root = directory.toRealPath()
        val cwd = Files.createDirectories(root.resolve("working/" + "nested/".repeat(30)))
        val sourceDirectory = when (layout) {
            "short" -> root.resolve("external")
            "long" -> root.resolve("external/" + "nested/".repeat(40))
            else -> cwd.resolve("target/scripts")
        }
        Files.createDirectories(sourceDirectory)
        val source = sourceDirectory.resolve(if (scenario == "unicode") "script café.dwl" else "script.dwl")
        source.writeText("%dw 2.0\noutput application/java\n---\npayload.value ++ vars.suffix ++ correlationId")
        val output = root.resolve("$layout-$scenario.log")
        val classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"))
            .split(File.pathSeparator).joinToString(File.pathSeparator) { File(it).absolutePath }
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", classpath, DataWeaveExternalResourceProbe::class.java.name,
            source.toString(), layout, scenario,
        ).directory(cwd.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Resource probe timed out: $layout")
            assertEquals(0, process.exitValue(), Files.readString(output))
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

/** Separate JVM gives the SDK a real controlled cwd without changing global JVM properties. */
object DataWeaveExternalResourceProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val source = Path.of(args[0])
        val cwd = Path.of("").toAbsolutePath()
        println("layout=${args[1]} cwd=$cwd source=$source")
        when (args[1]) {
            "short" -> check(!source.startsWith(cwd) && source.toString().length < cwd.toString().length)
            "long" -> check(!source.startsWith(cwd) && source.toString().length > cwd.toString().length)
            else -> check(source.startsWith(cwd))
        }
        val scenario = args[2]
        val executor = MuseDataWeaveExecutor()
        if (scenario == "symlink") {
            val target = Files.createDirectories(source.parent.resolve("other/deep"))
            target.parent.resolve("script.dwl").writeText("%dw 2.0\noutput application/java\n---\n'symlink-target'")
            val link = Files.createSymbolicLink(source.parent.resolve("alias"), target)
            val request = link.resolve("../script.dwl")
            check(executor.evaluateResource(request.toString(), DataWeaveContext(MuseEvent()))?.value == "symlink-target")
            return
        }
        if (scenario == "import") {
            source.writeText("%dw 2.0\nimport decorate from dataweave::ResourceHelpers\noutput application/java\n---\ndecorate('input')")
            check(executor.evaluateResource(source.toString(), DataWeaveContext(MuseEvent()))?.value == "resource:input")
            return
        }
        if (scenario == "diagnostic") {
            source.writeText("%dw 2.0\noutput application/java\n---\nunknownFunction()")
            val failure = runCatching { executor.evaluateResource(source.toString(), DataWeaveContext(MuseEvent())) }.exceptionOrNull()
            check(failure is DataWeaveException) { "Expected a source compilation failure" }
            val diagnostic = generateSequence(failure as Throwable) { it.cause }.joinToString("\n") { it.toString() }
            println(diagnostic)
            check("StringIndexOutOfBoundsException" !in diagnostic)
            check("line: 4" in diagnostic && "unknownFunction" in diagnostic)
            val expectedName = source.toString().removePrefix(File.separator).removeSuffix(".dwl").replace(File.separator, "::")
            check(expectedName in diagnostic) { "Missing original logical source name: $expectedName" }
            return
        }
        val context = DataWeaveContext(MuseEvent(
            payload = TypedValue.of(mapOf("value" to "file")),
            variables = mapOf("suffix" to TypedValue.of(":vars:")),
            correlationId = "correlation",
        ))
        val request = if (scenario == "relative") cwd.relativize(source).toString() else source.toString()
        val result = executor.evaluateResource(request, context)
        check(result?.value == "file:vars:correlation") { "Unexpected result: ${result?.value}" }
        check(result.mimeType == TypedValue.MIME_JAVA)
        check(result.charset == Charsets.UTF_8)
        if (scenario == "distinct") {
            val other = Files.createDirectories(source.parent.resolve("other")).resolve(source.fileName)
            other.writeText("%dw 2.0\noutput application/java\n---\n'second'")
            check(executor.evaluateResource(other.toString(), context)?.value == "second")
            check(executor.evaluateResource(source.toString(), context)?.value == "file:vars:correlation")
        }
        println("RESULT=file:vars:correlation MIME=${result.mimeType} CHARSET=${result.charset}")
    }
}
