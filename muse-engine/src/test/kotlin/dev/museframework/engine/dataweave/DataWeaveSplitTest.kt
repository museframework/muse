package dev.museframework.engine.dataweave

import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.expression.MuseExpressionException
import dev.museframework.engine.expression.MuseExpressionEvaluatorImpl
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.Properties
import kotlin.test.*

class DataWeaveSplitTest {
    @Test
    fun `split handles original expressions ranges and object entries`() {
        val evaluator = MuseExpressionEvaluatorImpl(MuseDataWeaveExecutor(), Properties())
        evaluator.split("#[[1,2,3] map ($ * 2)]", MuseEvent()).use {
            assertEquals(listOf(2, 4, 6), it.asSequence().map { item -> item.value }.toList())
        }
        evaluator.split("#[1 to 3]", MuseEvent()).use {
            assertEquals(listOf(1, 2, 3), it.asSequence().map { item -> item.value }.toList())
        }
        evaluator.split("#[{a: 1, b: 2}]", MuseEvent()).use {
            assertEquals(listOf(mapOf("a" to 1), mapOf("b" to 2)), it.asSequence().map { item -> item.value }.toList())
        }
        for (expression in listOf("#[null]", "#[42]", "#[true]", "#[\"text\"]")) {
            assertFailsWith<MuseExpressionException> { evaluator.split(expression, MuseEvent()) }
        }
    }

    @Test
    fun `CSV map and filter split advances progressively without ordinary value fallback`() {
        val bytes = buildString {
            appendLine("id,name")
            repeat(10000) { appendLine("$it,name-$it") }
        }.toByteArray()
        val input = CountingInput(bytes)
        val evaluator = MuseExpressionEvaluatorImpl(MuseDataWeaveExecutor(), Properties())
        val event = MuseEvent(payload = TypedValue.of(input, "application/csv; streaming=true; header=true"))
        evaluator.split("#[payload filter ((row) -> (row.id as Number) >= 2) map ((row) -> {id: row.id})]", event).use {
            assertTrue(input.consumed < bytes.size)
            assertEquals(mapOf("id" to "2"), it.next().value)
            assertTrue(input.consumed < bytes.size)
        }
        assertTrue(input.closed)
    }

    @Test
    fun `split preserves script headers and per element output metadata`() {
        val evaluator = MuseExpressionEvaluatorImpl(MuseDataWeaveExecutor(), Properties())
        evaluator.split("#[%dw 2.0\noutput application/json\nvar factor = 3\n---\n[1,2] map ($ * factor)]", MuseEvent()).use {
            val first = it.next()
            assertEquals("application/json", first.mimeType)
            assertEquals("3", (first.value as ByteArray).toString(first.charset))
            assertEquals("6", (it.next().value as ByteArray).toString(first.charset))
            assertFalse(it.hasNext())
        }
    }

    @Test
    fun `split streams JSON payload without discarding reader options`() {
        val bytes = (0 until 10000).joinToString(prefix = "[", postfix = "]") { "{\"id\":$it}" }.toByteArray()
        val input = CountingInput(bytes)
        val evaluator = MuseExpressionEvaluatorImpl(MuseDataWeaveExecutor(), Properties())
        val event = MuseEvent(payload = TypedValue.of(input, "application/json; streaming=true"))
        evaluator.split("#[payload map ((row) -> row.id)]", event).use {
            assertEquals(0, it.next().value)
            assertTrue(input.consumed < bytes.size)
        }
        assertTrue(input.closed)
    }

    @Test
    fun `deferred property lookup uses split properties and restores the calling binding`() {
        val outer = Properties().apply { setProperty("label", "outer") }
        val selected = Properties().apply { setProperty("label", "inner") }
        val evaluator = MuseExpressionEvaluatorImpl(MuseDataWeaveExecutor(), selected)
        MuseProperties.set(outer)
        try {
            evaluator.split("#[[1,2] map (() -> p('label'))]", MuseEvent()).use {
                assertSame(outer, MuseProperties.current())
                assertEquals(listOf("inner", "inner"), it.asSequence().map { item -> item.value }.toList())
                assertSame(outer, MuseProperties.current())
            }
        } finally {
            MuseProperties.clear()
        }
    }

    @Test
    fun `cached split execution keeps property bindings local to each caller`() {
        val backend = MuseDataWeaveExecutor()
        val expression = "#[[1,2] map ((item) -> p('label'))]"
        for (label in listOf("first", "second")) {
            val properties = Properties().apply { setProperty("label", label) }
            MuseExpressionEvaluatorImpl(backend, properties).split(expression, MuseEvent()).use {
                assertEquals(listOf(label, label), it.asSequence().map { item -> item.value }.toList())
            }
        }
    }

    @Test
    fun `Java split copies nested binary content before releasing the element context`() {
        val input = CountingInput("binary item".toByteArray())
        val event = MuseEvent(payload = TypedValue.of(input, "application/octet-stream"))
        val evaluator = MuseExpressionEvaluatorImpl(MuseDataWeaveExecutor(), Properties())
        evaluator.split("#[[{data: payload}]]", event).use {
            val item = it.next().value as Map<*, *>
            assertEquals("binary item", (item["data"] as ByteArray).toString(Charsets.UTF_8))
        }
        assertTrue(input.closed)
    }

    @Test
    fun `a deferred split expression failure releases the reader and preserves completed elements`() {
        val input = CountingInput("id\n1\n2\ninvalid\n".toByteArray())
        val event = MuseEvent(payload = TypedValue.of(input, "application/csv; streaming=true; header=true"))
        val evaluator = MuseExpressionEvaluatorImpl(MuseDataWeaveExecutor(), Properties())
        evaluator.split("#[payload map ((row) -> row.id as Number)]", event).use {
            assertEquals(1, it.next().value)
            assertEquals(2, it.next().value)
            assertFails { it.next() }
            assertTrue(input.closed)
        }
    }

    @Test
    fun `XML split applies streaming collectionPath reader options`() {
        val bytes = buildString {
            append("<records>")
            // The XML reader prebuffers a bounded window; exceed its 1.5 MiB threshold.
            repeat(100000) { append("<record><id>$it</id></record>") }
            append("</records>")
        }.toByteArray()
        val input = CountingInput(bytes)
        val event = MuseEvent(payload = TypedValue.of(input, "application/xml; streaming=true; collectionPath=records"))
        val evaluator = MuseExpressionEvaluatorImpl(MuseDataWeaveExecutor(), Properties())
        evaluator.split("#[payload.records.*record map ((row) -> row.id)]", event).use {
            assertEquals("0", it.next().value)
            assertTrue(input.consumed < bytes.size)
        }
        assertTrue(input.closed)
    }

    @Test
    fun `split closes owned iterator bindings on early exit and error while retaining source ownership`() {
        for (expression in listOf("#[payload map ($ * 2)]", "#[payload map ($ as Number)]")) {
            val values = if (expression.contains("as Number")) listOf<Any>(1, "invalid") else listOf<Any>(1, 2)
            val delegate = dev.museframework.engine.api.streaming.RepeatableInMemoryCursorIteratorProvider.fromIterable(values)
            var closes = 0
            val provider = object : dev.museframework.engine.api.streaming.MuseCursorIteratorProvider<Any> by delegate {
                override fun openCursor(): dev.museframework.engine.api.streaming.MuseCursorIterator<Any> {
                    val cursor = delegate.openCursor()
                    return object : dev.museframework.engine.api.streaming.MuseCursorIterator<Any> by cursor {
                        override fun close() { closes++; cursor.close() }
                    }
                }
            }
            val registry = dev.museframework.engine.api.streaming.StreamingResourceRegistry()
            registry.register(provider)
            val lease = registry.retain(listOf(provider))
            try {
                val event = MuseEvent(payload = TypedValue.of(provider))
                MuseExpressionEvaluatorImpl(MuseDataWeaveExecutor(), Properties()).split(expression, event).use {
                    assertEquals(if (expression.contains("as Number")) 1 else 2, it.next().value)
                    if (expression.contains("as Number")) assertFails { it.next() }
                }
                assertEquals(1, closes)
                assertFalse(provider.isClosed)
                registry.releaseAll()
                assertFalse(provider.isClosed)
            } finally {
                registry.releaseAll()
                lease.close()
            }
            assertTrue(provider.isClosed)
        }
    }

    @Test
    fun `split detaches binary resources inside JVM object arrays`() {
        val input = CountingInput("array binary".toByteArray())
        val event = MuseEvent(payload = TypedValue.of(input, "application/octet-stream"))
        MuseExpressionEvaluatorImpl(MuseDataWeaveExecutor(), Properties())
            .split("#[[([payload] as Array {class: \"java.lang.Object[]\"})]]", event).use {
                val item = it.next().value as Array<*>
                assertEquals("array binary", (item.single() as ByteArray).toString(Charsets.UTF_8))
            }
        assertTrue(input.closed)
    }

    @Test
    fun `split does not take ownership of a raw iterator supplied by its caller`() {
        for (expression in listOf("#[payload]", "#[[payload]]")) {
            var closed = false
            val source = object : Iterator<Int>, AutoCloseable {
                var current = 0
                override fun hasNext() = current < 3
                override fun next() = ++current
                override fun close() { closed = true }
            }
            val evaluator = MuseExpressionEvaluatorImpl(MuseDataWeaveExecutor(), Properties())
            evaluator.split(expression, MuseEvent(payload = TypedValue.of(source))).use {
                assertEquals(if (expression == "#[payload]") 1 else listOf(1, 2, 3), it.next().value)
            }
            assertFalse(closed)
            source.close()
        }
    }

    @Test
    fun `split opening preserves a fatal source error and closes its owned binding`() {
        val defect = AssertionError("source defect")
        val source = object : Iterator<Any> {
            override fun hasNext(): Boolean = throw defect
            override fun next(): Any = throw defect
        }
        val delegate = dev.museframework.engine.api.streaming.NonRepeatableCursorIteratorProvider(source)
        var closes = 0
        val provider = object : dev.museframework.engine.api.streaming.MuseCursorIteratorProvider<Any> by delegate {
            override fun openCursor(): dev.museframework.engine.api.streaming.MuseCursorIterator<Any> {
                val cursor = delegate.openCursor()
                return object : dev.museframework.engine.api.streaming.MuseCursorIterator<Any> by cursor {
                    override fun close() { closes++; cursor.close() }
                }
            }
        }
        try {
            val event = MuseEvent(payload = TypedValue.of(provider))
            val evaluator = MuseExpressionEvaluatorImpl(MuseDataWeaveExecutor(), Properties())
            val thrown = assertFailsWith<AssertionError> { evaluator.split("#[sizeOf(payload)]", event) }
            assertSame(defect, thrown)
            assertEquals(1, closes)
            assertFalse(provider.isClosed)
        } finally {
            provider.releaseResources()
        }
    }

    @Test
    fun `split applies reader options canonically and ignores unrelated MIME metadata`() {
        val evaluator = MuseExpressionEvaluatorImpl(MuseDataWeaveExecutor(), Properties())
        val xml = MuseEvent(payload = TypedValue.of(
            CountingInput("<records><record><id/></record></records>".toByteArray()),
            "application/xml; streaming=true; collectionpath=records; nullValueOn=none",
        ))
        evaluator.split("#[payload.records.*record map ((row) -> row.id)]", xml).use {
            assertEquals("", it.next().value)
        }
        val json = MuseEvent(payload = TypedValue.of(
            CountingInput("[1,2]".toByteArray()), "application/json; profile=example; streaming=true",
        ))
        evaluator.split("#[payload]", json).use {
            assertEquals(listOf(1, 2), it.asSequence().map { item -> item.value }.toList())
        }
        val invalid = MuseEvent(payload = TypedValue.of(
            CountingInput("<record/>".toByteArray()), "application/xml; maxAttributeSize=not-a-number",
        ))
        assertFailsWith<MuseExpressionException> { evaluator.split("#[payload]", invalid).use { } }
    }

    @Test
    fun `value evaluation restores the outer property binding on success and failure`() {
        val outer = Properties().apply { setProperty("label", "outer") }
        val selected = Properties().apply { setProperty("label", "inner") }
        val backend = MuseDataWeaveExecutor()
        MuseProperties.set(outer)
        try {
            assertEquals("inner", backend.evaluate("p('label')", DataWeaveContext(MuseEvent(), selected))?.value)
            assertSame(outer, MuseProperties.current())
            assertFailsWith<MuseExpressionException> { backend.evaluate("p('missing')", DataWeaveContext(MuseEvent(), selected)) }
            assertSame(outer, MuseProperties.current())
        } finally {
            MuseProperties.clear()
        }
    }

    @Test
    fun `resource evaluation receives its own properties and restores the caller binding`() {
        val script = java.nio.file.Files.createTempFile("muse-property-resource-", ".dwl")
        java.nio.file.Files.writeString(script, "output application/java --- p('label')")
        val outer = Properties().apply { setProperty("label", "outer") }
        val selected = Properties().apply { setProperty("label", "inner") }
        MuseProperties.set(outer)
        try {
            val value = MuseDataWeaveExecutor().evaluateResource(script.toString(), DataWeaveContext(MuseEvent(), selected))
            assertEquals("inner", value?.value)
            assertSame(outer, MuseProperties.current())
        } finally {
            MuseProperties.clear()
            java.nio.file.Files.deleteIfExists(script)
        }
    }

    private class CountingInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var consumed = 0
        var closed = false
        override fun read(): Int = super.read().also { if (it >= 0) consumed++ }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) consumed += it }
        override fun close() { closed = true; super.close() }
    }
}
