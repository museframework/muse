package dev.museframework.babelfish.model

data class RawFlow(
    val name: String,
    val source: RawElement?,
    val pipeline: List<RawElement>,
    val errorHandler: RawErrorHandler?,
    val location: SourceLocation,
    val docId: String? = null,
)

data class RawErrorHandler(
    val strategies: List<RawElement>
)
