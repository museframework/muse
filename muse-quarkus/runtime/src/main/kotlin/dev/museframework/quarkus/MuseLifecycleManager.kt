package dev.museframework.quarkus

import dev.museframework.engine.app.MuseApplication
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.event.Observes
import jakarta.inject.Singleton
import kotlinx.coroutines.runBlocking
import org.jboss.logging.Logger

@Singleton
class MuseLifecycleManager(
    private val application: MuseApplication,
) {
    private val logger = Logger.getLogger(MuseLifecycleManager::class.java)

    fun onStartup(@Observes event: StartupEvent) {
        logger.info("Starting Muse application")
        runBlocking {
            application.initIfNeeded()
            application.start()
        }
    }

    fun onShutdown(@Observes event: ShutdownEvent) {
        logger.info("Stopping Muse application")
        runBlocking { application.stop() }
    }
}
