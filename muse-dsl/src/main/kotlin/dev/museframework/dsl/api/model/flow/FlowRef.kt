package dev.museframework.dsl.api.model.flow

import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.model.component.Router

sealed class FlowRef {
    data class ByReference(
        val flow: MuseFlow,
    ) : FlowRef()

    data class ByName(
        val name: String,
    ) : FlowRef()
}

data class FlowRefConfig(
    override val docName: String,
    override val docId: String,
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val ref: FlowRef,
    val targetConfig: TargetConfig? = null,
) : Router
