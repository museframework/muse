package dev.museframework.test.nativeapp

import dev.museframework.dsl.annotation.MuseConfig
import dev.museframework.dsl.api.model.config.MuseConfiguration
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.springboot.MuseConfigProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Native-compatible configuration using MuseConfigProvider.
 * This approach does NOT use reflection and is GraalVM native-image compatible.
 */
@MuseConfig
@Component
class NativeConfig
    @Autowired
    constructor(
        private val simpleFlow: SimpleFlow,
    ) : MuseConfigProvider {
        override fun flows(): List<MuseFlow> =
            listOf(
                simpleFlow.helloFlow,
            )

        override fun configs(): List<MuseConfiguration> = emptyList()
    }
