package dev.museframework.springboot

import dev.museframework.engine.app.MuseApplication
import kotlinx.coroutines.runBlocking
import org.springframework.context.SmartLifecycle


class MuseLifecycle(
    private val museApplication: MuseApplication,
) : SmartLifecycle {
    private val logger = org.slf4j.LoggerFactory.getLogger(MuseLifecycle::class.java)

    private var running = false

    override fun start() {
        logger.info("MuseLifecycle.start() called")
        runBlocking {
            museApplication.initIfNeeded()
            museApplication.start()
        }
        running = true
    }
    
    override fun stop() {
        runBlocking {
            museApplication.stop()
        }
        running = false
    }

    override fun isRunning(): Boolean = running

    override fun getPhase(): Int = Int.MAX_VALUE

    override fun isPauseable(): Boolean = false
}
