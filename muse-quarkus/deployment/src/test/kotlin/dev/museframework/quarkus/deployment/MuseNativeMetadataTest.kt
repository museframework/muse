package dev.museframework.quarkus.deployment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MuseNativeMetadataTest {
    @Test
    fun `registers engine service providers for native images`() {
        val serviceProviders = MuseExtensionProcessor().serviceProviders()
        val providers = serviceProviders.flatMap { it.providers() }

        assertTrue(providers.contains("dev.museframework.engine.factory.MuseEngineRegistrar"))
        assertTrue(providers.contains("dev.museframework.engine.dataweave.MuseRuntimeModule"))
        assertEquals(8, serviceProviders.size)
    }

    @Test
    fun `includes the Muse DataWeave module in native images`() {
        val patterns = MuseExtensionProcessor().dataWeaveResources().includePatterns

        assertTrue(patterns.any { Regex(it).matches("dw/Core.dwl") })
        assertTrue(patterns.any { Regex(it).matches("dw/Muse/Runtime.dwl") })
    }
}
