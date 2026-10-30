package dev.museframework.test

import dev.museframework.springboot.MuseApplication
import dev.museframework.springboot.runMuseApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@MuseApplication(webEnvironment = WebApplicationType.SERVLET)
class Application

fun main(args: Array<String>) = runMuseApplication<Application>(args)
