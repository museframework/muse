package dev.museframework.springboot

import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootApplication
annotation class MuseApplication(
    val name: String = "",
    val scanBasePackages: Array<String> = [],
    val defaultProfiles: Array<String> = [],
    val webEnvironment: WebApplicationType = WebApplicationType.NONE,
)