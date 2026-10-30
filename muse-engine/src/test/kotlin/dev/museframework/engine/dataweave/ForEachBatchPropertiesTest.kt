package dev.museframework.engine.dataweave

import dev.museframework.dsl.api.config.ForEachConfig
import dev.museframework.engine.execution.policy.resolveForEachBatchSize
import org.junit.jupiter.api.Test
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ForEachBatchPropertiesTest {
    private fun config(expression: String? = null, size: Int = 1) = ForEachConfig(
        docName = "batch", docId = "batch", collection = null, pipeline = emptyList(),
        batchSize = size, batchSizeExpression = expression,
    )

    @Test
    fun `property-based batch overrides static size and honors property defaults`() {
        val defaults = Properties().apply { setProperty("batch.size", "2") }
        assertEquals(2, resolveForEachBatchSize(config("\${batch.size}", 10), Properties(defaults)))
        assertEquals(10, resolveForEachBatchSize(config(size = 10), Properties()))
    }

    @Test
    fun `missing invalid and event expression batch sizes fail before iteration`() {
        for (expression in listOf("\${missing}", "#[vars.batch]", "zero", "0", "-1")) {
            assertFailsWith<IllegalArgumentException> { resolveForEachBatchSize(config(expression), Properties()) }
        }
        assertFailsWith<IllegalArgumentException> { resolveForEachBatchSize(config(size = 0), Properties()) }
    }
}
