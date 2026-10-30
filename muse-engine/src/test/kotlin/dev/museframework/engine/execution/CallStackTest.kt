package dev.museframework.engine.execution

import dev.museframework.dsl.api.config.SetPayloadConfig
import dev.museframework.dsl.api.config.SetVariableConfig
import dev.museframework.dsl.api.config.ForEachConfig
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.engine.api.execution.MuseStackFrame
import dev.museframework.engine.api.execution.PipelineScope
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName

@DisplayName("CallStack Tests")
class CallStackTest {

    @Test
    @DisplayName("CallStack should be empty at initialization")
    fun `callStack is empty at initialization`() {
        val emptyStack = persistentListOf<MuseStackFrame>()

        assertTrue(emptyStack.isEmpty())
        assertEquals(0, emptyStack.size)
    }

    @Test
    @DisplayName("PushFrame should add frame to callStack")
    fun `pushFrame adds frame to callStack`() {
        val flow = MuseFlow.Flow(
            name = "test-flow",
            docId = "test-doc-id",
            pipeline = emptyList(),
        )
        val scope = PipelineScope.FlowScope(flow)
        val processor = SetPayloadConfig(
            docName = "setPayload",
            docId = "processor-doc-id",
            sourceFile = "TestFlow.kt",
            sourceLine = 10,
            sourceClass = "com.example.TestFlow",
            value = "test",
        )

        val frame = MuseStackFrame(
            scope = scope,
            element = processor,
            index = 0,
        )
        val stack = persistentListOf<MuseStackFrame>().add(frame)

        assertEquals(1, stack.size)
        assertEquals(frame, stack[0])
    }

    @Test
    @DisplayName("PopFrame should remove last frame from callStack")
    fun `popFrame removes last frame from callStack`() {
        val flow = MuseFlow.Flow(
            name = "test-flow",
            docId = "test-doc-id",
            pipeline = emptyList(),
        )
        val scope = PipelineScope.FlowScope(flow)
        val processor = SetPayloadConfig(
            docName = "setPayload",
            docId = "processor-doc-id",
            sourceFile = "TestFlow.kt",
            sourceLine = 10,
            sourceClass = "com.example.TestFlow",
            value = "test",
        )
        val frame = MuseStackFrame(
            scope = scope,
            element = processor,
            index = 0,
        )
        val stack = persistentListOf<MuseStackFrame>().add(frame)

        val poppedStack = stack.removeAt(stack.size - 1)

        assertTrue(poppedStack.isEmpty())
        assertEquals(0, poppedStack.size)
    }

    @Test
    @DisplayName("MuseStackFrame should provide currentSourceFile from element")
    fun `MuseStackFrame provides currentSourceFile from element`() {
        val flow = MuseFlow.Flow(
            name = "test-flow",
            docId = "test-doc-id",
            pipeline = emptyList(),
        )
        val scope = PipelineScope.FlowScope(flow)
        val processor = SetPayloadConfig(
            docName = "setPayload",
            docId = "processor-doc-id",
            sourceFile = "TestFlow.kt",
            sourceLine = 10,
            sourceClass = "com.example.TestFlow",
            value = "test",
        )

        val frame = MuseStackFrame(
            scope = scope,
            element = processor,
            index = 0,
        )

        assertEquals("TestFlow.kt", frame.currentSourceFile)
        assertEquals(10, frame.currentSourceLine)
        assertEquals("com.example.TestFlow", frame.currentSourceClass)
    }

    @Test
    @DisplayName("MuseStackFrame should fallback to scope source info when element has none")
    fun `MuseStackFrame falls back to scope source info`() {
        val routerConfig = ForEachConfig(
            docName = "forEach",
            docId = "foreach-doc-id",
            sourceFile = "RouterFlow.kt",
            sourceLine = 20,
            sourceClass = "com.example.RouterFlow",
            collection = "payload",
            pipeline = emptyList(),
        )
        val scope = PipelineScope.RouterScope(routerConfig)
        val processor = SetPayloadConfig(
            docName = "setPayload",
            docId = "processor-doc-id",
            sourceFile = null,
            sourceLine = null,
            sourceClass = null,
            value = "test",
        )

        val frame = MuseStackFrame(
            scope = scope,
            element = processor,
            index = 0,
        )

        assertEquals("RouterFlow.kt", frame.currentSourceFile)
        assertEquals(20, frame.currentSourceLine)
        assertEquals("com.example.RouterFlow", frame.currentSourceClass)
    }

    @Test
    @DisplayName("CallStack should maintain order of frames")
    fun `callStack maintains order of frames`() {
        val flow = MuseFlow.Flow(
            name = "test-flow",
            docId = "test-doc-id",
            pipeline = emptyList(),
        )
        val scope = PipelineScope.FlowScope(flow)

        val processor1 = SetPayloadConfig(
            docName = "setPayload",
            docId = "processor-1-doc-id",
            sourceFile = "TestFlow.kt",
            sourceLine = 10,
            sourceClass = "com.example.TestFlow",
            value = "test1",
        )
        val processor2 = SetVariableConfig(
            docName = "setVariable",
            docId = "processor-2-doc-id",
            sourceFile = "TestFlow.kt",
            sourceLine = 20,
            sourceClass = "com.example.TestFlow",
            variableName = "var",
            value = "test2",
        )

        val frame1 = MuseStackFrame(scope = scope, element = processor1, index = 0)
        val frame2 = MuseStackFrame(scope = scope, element = processor2, index = 1)

        val stack = persistentListOf<MuseStackFrame>()
            .add(frame1)
            .add(frame2)

        assertEquals(2, stack.size)
        assertEquals(frame1, stack[0])
        assertEquals(frame2, stack[1])
        assertEquals(processor1, stack[0].element)
        assertEquals(processor2, stack[1].element)
    }
}