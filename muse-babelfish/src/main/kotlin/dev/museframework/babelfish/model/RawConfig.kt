package dev.museframework.babelfish.model

data class RawConfig(
    val id: String,
    val namespace: String,
    val tag: String,
    val attrs: Map<String, String>,
    val children: List<RawElement>,
    val location: SourceLocation,
    val textContent: String? = null,
)
