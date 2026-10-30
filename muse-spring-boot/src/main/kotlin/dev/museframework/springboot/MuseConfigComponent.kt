package dev.museframework.springboot

import dev.museframework.dsl.annotation.MuseConfig
import org.springframework.stereotype.Component

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@MuseConfig
@Component
annotation class MuseConfigComponent
