package dev.museframework.babelfish.model

data class RawFlow(
    val name: String,
    val source: RawElement?,
    val pipeline: List<RawElement>,
    val errorHandler: RawErrorHandler?,
    val location: SourceLocation
)

data class RawErrorHandler(
    val strategies: List<RawElement>
)
