package dev.museframework.springboot

import dev.museframework.engine.app.MuseBanner
import org.springframework.boot.Banner
import org.springframework.boot.SpringBootVersion
import org.springframework.core.env.Environment
import java.io.PrintStream

object MuseBannerPrinter : Banner {
    override fun printBanner(environment: Environment, sourceClass: Class<*>?, out: PrintStream) {
        MuseBanner.printTo(out, platform = "Spring Boot", version = SpringBootVersion.getVersion())
    }
}
