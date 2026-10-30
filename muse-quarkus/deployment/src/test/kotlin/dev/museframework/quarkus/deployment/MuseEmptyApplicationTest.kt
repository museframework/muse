package dev.museframework.quarkus.deployment

import dev.museframework.engine.app.MuseApplication
import dev.museframework.quarkus.MuseLifecycleManager
import io.quarkus.test.QuarkusUnitTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class MuseEmptyApplicationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val unitTest: QuarkusUnitTest = QuarkusUnitTest().withEmptyApplication()
    }

    @Inject
    lateinit var application: MuseApplication

    @Inject
    lateinit var lifecycleManager: MuseLifecycleManager

    @Test
    fun `empty application starts with extension beans installed`() {
        assertTrue(application.context.flows.isEmpty())
        assertTrue(application.context.configs.isEmpty())
        assertTrue(::lifecycleManager.isInitialized)
    }
}
