package dev.museframework.engine.api.expression

import dev.museframework.engine.api.event.TypedValue

/** One caller-owned split into typed elements; close on every exit. Input replay is independent. */
class ExpressionSplit private constructor(
    private val iteration: ExpressionIteration,
) : Iterator<TypedValue<Any>>, AutoCloseable {
    override fun hasNext(): Boolean = iteration.hasNext()

    @Suppress("UNCHECKED_CAST")
    override fun next(): TypedValue<Any> {
        val value = iteration.next()
        return if (value is TypedValue<*>) value as TypedValue<Any> else TypedValue.of(value)
    }

    override fun close() = iteration.close()

    companion object {
        /** Transfers a newly created typed iterator and its cleanup to the split consumer. */
        fun owned(iterator: Iterator<TypedValue<Any>>): ExpressionSplit =
            ExpressionSplit(ExpressionIteration.owned(iterator))

        /** Compatibility bridge; adopts only this local iteration, not its input providers. */
        fun fromIteration(iteration: ExpressionIteration): ExpressionSplit = ExpressionSplit(iteration)
    }
}
