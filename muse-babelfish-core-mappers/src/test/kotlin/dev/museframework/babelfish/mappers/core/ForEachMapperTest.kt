package dev.museframework.babelfish.mappers.core

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.babelfish.model.SourceLocation
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ForEachMapperTest {
    @Test
    fun `property-based batch size survives mapping and Kotlin emission`() {
        val raw = RawElement(namespace = "core", tag = "foreach", attrs = mapOf("batchSize" to "\${sftp.origin.pulling.size}"), children = emptyList(), location = SourceLocation(1, 1))
        val config = ForEachMapper.map(raw, MappingContext(BabelfishRegistry(), Path.of("batch.xml")))
        assertContains(ForEachCodeEmitter.emit(config, CodegenContext("example")), "batchSizeExpression = \"\${'$'}{sftp.origin.pulling.size}\"")
    }

    @Test
    fun `literal batch size remains typed`() {
        val raw = RawElement(namespace = "core", tag = "foreach", attrs = mapOf("batchSize" to "10"), children = emptyList(), location = SourceLocation(1, 1))
        val config = ForEachMapper.map(raw, MappingContext(BabelfishRegistry(), Path.of("batch.xml")))
        assertEquals(10, config.batchSize)
    }
}
