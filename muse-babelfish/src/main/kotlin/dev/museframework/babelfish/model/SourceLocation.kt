package dev.museframework.babelfish.model

data class SourceLocation(val line: Int, val col: Int) {
    override fun toString(): String = "line $line, col $col"
}
