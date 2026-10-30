package dev.museframework.springboot

import kotlinx.coroutines.runBlocking
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import java.util.*

inline fun <reified T : Any> runMuseApplication(args: Array<String> = emptyArray()) {
    val annotation = T::class.annotations
        .filterIsInstance<MuseApplication>()
        .firstOrNull()

    val webAppType = annotation?.webEnvironment ?: WebApplicationType.NONE
    System.setProperty("spring.main.web-application-type", webAppType.name.lowercase())

    if (annotation?.defaultProfiles?.isNotEmpty() == true) {
        System.setProperty("spring.profiles.active", annotation.defaultProfiles.joinToString(","))
    }

    // disabilita il banner di Spring Boot — usiamo il nostro
    //System.setProperty("spring.main.banner-mode", "off")

    val buildProperties = runCatching {
        val props = Properties()
        T::class.java.classLoader
            .getResourceAsStream("META-INF/build-info.properties")
            ?.let { props.load(it) }
        props
    }.getOrNull()

    val artifactId = buildProperties?.getProperty("build.artifact")
    val version = buildProperties?.getProperty("build.version")

    val appName = when {
        annotation?.name?.isNotBlank() == true -> annotation.name
        artifactId != null -> artifactId
        else -> T::class.simpleName ?: "muse-app"
    }

    System.setProperty("spring.application.name", appName)
    version?.let { System.setProperty("muse.application.version", it) }

    val springApp = SpringApplication(T::class.java)
    springApp.setBanner(MuseBannerPrinter)

    val ctx = springApp.run(*args)
    val museApp = ctx.getBean(dev.museframework.engine.app.MuseApplication::class.java)
    val lifecycle = ctx.getBean(MuseLifecycle::class.java)

    // aspetta che il lifecycle abbia avviato i flow
    while (!lifecycle.isRunning) {
        Thread.sleep(50)
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        println("\nShutting down...")
        runBlocking {
            museApp.stop()
        }
    })

    Thread.currentThread().join()
}
