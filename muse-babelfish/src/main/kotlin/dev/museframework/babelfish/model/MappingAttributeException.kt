package dev.museframework.babelfish.model

class MappingAttributeException(
    val attrName: String,
    val tag: String,
    val namespace: String,
    val location: SourceLocation
) : Exception("Required attribute '$attrName' missing on '$namespace:$tag' at $location")
