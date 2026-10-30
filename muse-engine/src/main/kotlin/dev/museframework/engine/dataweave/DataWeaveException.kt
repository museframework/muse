package dev.museframework.engine.dataweave

import dev.museframework.engine.api.expression.MuseExpressionException

class DataWeaveException(message: String, cause: Throwable? = null) : MuseExpressionException(message, cause)
