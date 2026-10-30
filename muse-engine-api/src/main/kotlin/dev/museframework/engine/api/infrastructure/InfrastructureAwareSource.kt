package dev.museframework.engine.api.infrastructure

import dev.museframework.engine.api.source.MuseSource

interface InfrastructureAwareSource : MuseSource {
    fun getInfrastructureRequirements(): List<InfrastructureRequirement>
}
