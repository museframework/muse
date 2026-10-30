package dev.museframework.engine.app

import dev.museframework.dsl.validation.ValidationError

class MuseBootstrapException(
    val errors: List<ValidationError>,
) : RuntimeException(
    "Bootstrap failed with ${errors.size} error(s):\n" +
            errors.joinToString("\n") { "  - ${it.message}" }
)
