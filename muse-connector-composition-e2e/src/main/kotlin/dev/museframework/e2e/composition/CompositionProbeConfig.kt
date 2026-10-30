package dev.museframework.e2e.composition

import dev.museframework.dsl.api.model.component.Processor
import java.util.UUID

enum class ProbePhase {
    ADMISSION,
    SUCCESS,
    FAILURE,
}

data class CompositionProbeConfig(
    val phase: ProbePhase,
    override val docName: String,
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
) : Processor
