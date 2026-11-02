package dev.museframework.engine.integration

import dev.museframework.dsl.api.model.config.MuseConfiguration
import dev.museframework.engine.app.MuseApplicationConfig
import dev.museframework.engine.app.bootstrap
import dev.museframework.engine.factory.MuseComponentFactory
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class BootstrapDuplicateConfigurationTest {
    @Test
    fun `bootstrap applies every configuration sharing a documentation label`() {
        val applied = mutableListOf<String>()
        val factory = MuseComponentFactory().apply {
            registerConfig(NamedConfig::class) { config, _ -> applied += config.docId }
        }
        bootstrap(
            MuseApplicationConfig(
                flows = emptyList(),
                configs = listOf(NamedConfig("state", "first"), NamedConfig("state", "second")),
                factory = factory,
            ),
        )

        assertEquals(listOf("first", "second"), applied)
    }

    private data class NamedConfig(
        override val docName: String,
        override val docId: String = docName,
        override val docDescription: String? = null,
        override val sourceFile: String? = null,
        override val sourceLine: Int? = null,
        override val sourceClass: String? = null,
    ) : MuseConfiguration
}
