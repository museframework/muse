package dev.museframework.e2e.composition

import dev.museframework.springboot.MuseApplication
import dev.museframework.springboot.runMuseApplication
import org.springframework.boot.WebApplicationType
import org.springframework.context.annotation.ComponentScan

fun main(args: Array<String>) {
    runMuseApplication<CompositionApplicationMarker>(args)
}

@MuseApplication(webEnvironment = WebApplicationType.NONE)
@ComponentScan(basePackages = ["dev.museframework.e2e.composition", "dev.museframework"])
class CompositionApplicationMarker
