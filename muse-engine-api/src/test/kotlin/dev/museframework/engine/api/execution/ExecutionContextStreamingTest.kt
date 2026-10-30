package dev.museframework.engine.api.execution

import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.engine.api.streaming.RepeatableInMemoryCursorStreamProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ExecutionContextStreamingTest {

    @Test
    fun `child context shares parent streaming registry`() {
        val evaluator = object : MuseExpressionEvaluator {
            override fun evaluate(expression: String, event: MuseEvent): dev.museframework.engine.api.event.TypedValue<Any>? = null
        }
        val parent = ExecutionContext(
            event = MuseEvent(),
            errorHandler = null,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            expressionEvaluator = evaluator,
        )
        val child = parent.childContext(
            event = MuseEvent(),
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
        )

        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("abc".toByteArray())
        val cursor = provider.openCursor()
        child.streamingResources.register(provider)
        parent.streamingResources.releaseAll()

        assertSame(parent.streamingResources, child.streamingResources)
        assertTrue(cursor.isReleased)
    }
}
