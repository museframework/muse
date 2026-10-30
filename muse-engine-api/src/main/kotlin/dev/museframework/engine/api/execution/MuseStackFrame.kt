package dev.museframework.engine.api.execution

import dev.museframework.dsl.api.model.component.MuseComponent

data class MuseStackFrame(
    val scope: PipelineScope,
    val element: MuseComponent,
    val index: Int,
) {
    val currentSourceFile: String?
        get() = element.sourceFile ?: scope.sourceFile
    val currentSourceLine: Int?
        get() = element.sourceLine ?: scope.sourceLine
    val currentSourceClass: String?
        get() = element.sourceClass ?: scope.sourceClass
}
