package dev.museframework.engine.api.execution

import dev.museframework.dsl.api.model.component.Router
import dev.museframework.dsl.api.model.flow.MuseFlow

sealed class PipelineScope {
    abstract val name: String
    abstract val docId: String
    abstract val sourceFile: String?
    abstract val sourceLine: Int?
    abstract val sourceClass: String?

    data class FlowScope(
        val flow: MuseFlow.Flow,
    ) : PipelineScope() {
        override val name = flow.name
        override val docId = flow.docId
        override val sourceFile = null
        override val sourceLine = null
        override val sourceClass = null
    }

    data class SubFlowScope(
        val subFlow: MuseFlow.SubFlow,
    ) : PipelineScope() {
        override val name = subFlow.name
        override val docId = subFlow.docId
        override val sourceFile = null
        override val sourceLine = null
        override val sourceClass = null
    }

    data class RouterScope(
        val router: Router,
    ) : PipelineScope() {
        override val name = router.docName
        override val docId = router.docId
        override val sourceFile = router.sourceFile
        override val sourceLine = router.sourceLine
        override val sourceClass = router.sourceClass
    }
}
