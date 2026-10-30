package dev.museframework.test.nativeapp

import dev.museframework.springboot.MuseApplication
import dev.museframework.springboot.runMuseApplication
import org.springframework.boot.WebApplicationType

@MuseApplication(webEnvironment = WebApplicationType.SERVLET)
class Application

fun main(args: Array<String>) = runMuseApplication<Application>(args)
