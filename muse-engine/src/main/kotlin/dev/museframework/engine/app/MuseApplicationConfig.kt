package dev.museframework.engine.app

import dev.museframework.dsl.api.model.config.MuseConfiguration
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.engine.api.execution.policy.BackgroundExecutionConfig
import dev.museframework.engine.factory.MuseComponentFactory

data class MuseApplicationConfig(
    val flows: List<MuseFlow>,
    val configs: List<MuseConfiguration> = emptyList(),
    val factory: MuseComponentFactory,
    val background: BackgroundExecutionConfig = BackgroundExecutionConfig(),
)
