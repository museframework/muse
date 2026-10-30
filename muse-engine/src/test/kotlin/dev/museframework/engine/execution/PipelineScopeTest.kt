package dev.museframework.engine.execution

import dev.museframework.dsl.api.config.ForEachConfig
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.engine.api.execution.PipelineScope
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName

@DisplayName("PipelineScope Tests")
class PipelineScopeTest {

    @Test
    @DisplayName("FlowScope should extract flow properties correctly")
    fun `FlowScope extracts flow properties`() {
        val flow = MuseFlow.Flow(
            name = "test-flow",
            docId = "test-doc-id",
            pipeline = emptyList(),
        )

        val scope = PipelineScope.FlowScope(flow)

        assertEquals("test-flow", scope.name)
        assertEquals("test-doc-id", scope.docId)
        assertNull(scope.sourceFile)
        assertNull(scope.sourceLine)
        assertNull(scope.sourceClass)
    }

    @Test
    @DisplayName("SubFlowScope should extract subflow properties correctly")
    fun `SubFlowScope extracts subflow properties`() {
        val subFlow = MuseFlow.SubFlow(
            name = "test-subflow",
            docId = "test-subflow-doc-id",
            pipeline = emptyList(),
        )

        val scope = PipelineScope.SubFlowScope(subFlow)

        assertEquals("test-subflow", scope.name)
        assertEquals("test-subflow-doc-id", scope.docId)
        assertNull(scope.sourceFile)
        assertNull(scope.sourceLine)
        assertNull(scope.sourceClass)
    }

    @Test
    @DisplayName("RouterScope should extract router properties with source info")
    fun `RouterScope extracts router properties with source info`() {
        val routerConfig = ForEachConfig(
            docName = "forEach",
            docId = "foreach-doc-id",
            sourceFile = "TestFlow.kt",
            sourceLine = 42,
            sourceClass = "com.example.TestFlow",
            collection = "payload",
            pipeline = emptyList(),
        )

        val scope = PipelineScope.RouterScope(routerConfig)

        assertEquals("forEach", scope.name)
        assertEquals("foreach-doc-id", scope.docId)
        assertEquals("TestFlow.kt", scope.sourceFile)
        assertEquals(42, scope.sourceLine)
        assertEquals("com.example.TestFlow", scope.sourceClass)
    }

    @Test
    @DisplayName("RouterScope should handle null source info")
    fun `RouterScope handles null source info`() {
        val routerConfig = ForEachConfig(
            docName = "forEach",
            docId = "foreach-doc-id",
            sourceFile = null,
            sourceLine = null,
            sourceClass = null,
            collection = "payload",
            pipeline = emptyList(),
        )

        val scope = PipelineScope.RouterScope(routerConfig)

        assertEquals("forEach", scope.name)
        assertEquals("foreach-doc-id", scope.docId)
        assertNull(scope.sourceFile)
        assertNull(scope.sourceLine)
        assertNull(scope.sourceClass)
    }
}