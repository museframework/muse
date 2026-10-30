package dev.museframework.e2e.composition

import dev.museframework.springboot.MuseLifecycle
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

@Component
class FixtureReadyMarker(
    private val museLifecycle: MuseLifecycle,
) : ApplicationListener<ApplicationReadyEvent> {
    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        if (!museLifecycle.isRunning) {
            return
        }
        val marker = Path.of(System.getProperty("user.dir")).resolve(CONTEXT_READY_FILE)
        marker.parent?.let(Files::createDirectories)
        Files.writeString(marker, "ready")
    }

    companion object {
        const val CONTEXT_READY_FILE: String = "context-ready"
    }
}
