package dev.museframework.springboot.infrastructure

import dev.museframework.engine.api.infrastructure.InfrastructureAwareSource
import dev.museframework.engine.api.infrastructure.WebServerRequirement
import org.springframework.context.ApplicationContext
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class MuseInfrastructureHandler(
    private val applicationContext: ApplicationContext,
) {
    private val logger = org.slf4j.LoggerFactory.getLogger(MuseInfrastructureHandler::class.java)

    @EventListener(org.springframework.boot.context.event.ApplicationReadyEvent::class)
    fun onApplicationReady() {
        collectAndFulfillRequirements()
    }

    private fun collectAndFulfillRequirements() {
        val sources = applicationContext.getBeansOfType(InfrastructureAwareSource::class.java).values
        val requirements = sources.flatMap { it.getInfrastructureRequirements() }

        val needsWebServer = requirements.any { it == WebServerRequirement }

        if (needsWebServer) {
            logger.info("Web server requirement detected from HTTP listener sources")
            logger.info("Infrastructure setup complete - web server will be started by Spring Boot")
        }
    }

    @EventListener
    fun onContextClosed(event: ContextClosedEvent) {
        logger.info("Shutting down infrastructure")
    }
}
