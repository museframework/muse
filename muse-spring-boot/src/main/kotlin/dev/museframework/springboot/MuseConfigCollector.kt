package dev.museframework.springboot

import dev.museframework.dsl.api.config.MuseConfigDefinition
import dev.museframework.dsl.api.model.config.MuseConfiguration
import dev.museframework.dsl.api.model.flow.MuseFlow
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible


internal fun collectFromConfigDefinition(instance: MuseConfigDefinition): Pair<List<MuseFlow>, List<MuseConfiguration>> {
    val flows = mutableListOf<MuseFlow>()
    val configs = mutableListOf<MuseConfiguration>()

    instance::class.memberProperties.forEach { prop ->
        prop.isAccessible = true
        when (val value = prop.getter.call(instance)) {
            is MuseFlow -> flows.add(value)
            is MuseConfiguration -> configs.add(value)
        }
    }

    return flows to configs
}
