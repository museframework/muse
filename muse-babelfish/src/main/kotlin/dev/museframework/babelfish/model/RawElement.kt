package dev.museframework.babelfish.model

data class RawElement(
    val namespace: String,
    val tag: String,
    val attrs: Map<String, String>,
    val children: List<RawElement>,
    val location: SourceLocation,
    val textContent: String? = null,
    /** Full XML leaf text where leading and trailing whitespace is data. */
    val untrimmedTextContent: String? = null,
) {
    fun attr(name: String): String? = attrs[name]

    fun requireAttr(
        name: String,
        loc: SourceLocation,
    ): String = attrs[name] ?: throw MappingAttributeException(name, tag, namespace, loc)
}
