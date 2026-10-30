package dev.museframework.engine.app

import java.io.PrintStream
import java.nio.charset.StandardCharsets

object MuseBanner {
    private val logoLines: List<String> by lazy {
        requireNotNull(MuseBanner::class.java.getResourceAsStream("/default_banner.txt")) {
            "Missing Muse banner resource: default_banner.txt"
        }.bufferedReader(StandardCharsets.UTF_8).use { it.readLines() }
    }

    fun printTo(
        out: PrintStream,
        platform: String,
        version: String,
    ) {
        logoLines.forEach(out::println)
        out.println()
        out.println("Powered by $platform $version")
        out.println()
    }
}
