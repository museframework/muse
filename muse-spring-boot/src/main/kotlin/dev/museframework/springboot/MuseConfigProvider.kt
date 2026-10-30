package dev.museframework.springboot

import dev.museframework.dsl.api.model.config.MuseConfiguration
import dev.museframework.dsl.api.model.flow.MuseFlow

interface MuseConfigProvider {
    fun flows(): List<MuseFlow> = emptyList()
    fun configs(): List<MuseConfiguration> = emptyList()
}
