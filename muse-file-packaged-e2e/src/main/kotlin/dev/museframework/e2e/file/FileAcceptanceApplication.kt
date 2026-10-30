package dev.museframework.e2e.file

import dev.museframework.springboot.MuseApplication
import dev.museframework.springboot.runMuseApplication
import org.springframework.boot.WebApplicationType
import org.springframework.context.annotation.ComponentScan

/**
 * Headless packaged acceptance entry point. Generated [dev.museframework.e2e.file.generated]
 * configuration is discovered through component scan; the plugin-emitted SERVLET main remains
 * compilation evidence only.
 */
fun main(args: Array<String>) {
    runMuseApplication<FileAcceptanceApplicationMarker>(args)
}

@MuseApplication(webEnvironment = WebApplicationType.NONE)
@ComponentScan(basePackages = ["dev.museframework.e2e.file", "dev.museframework.e2e.file.generated", "dev.museframework"])
class FileAcceptanceApplicationMarker
