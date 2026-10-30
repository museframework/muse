package dev.museframework.babelfish.emitter

/**
 * Runtime-specific shape of a generated application configuration class.
 *
 * Babelfish owns the class body, while runtime integrations provide the
 * annotations and optional provider interface required for discovery.
 */
data class ConfigurationClassSpec(
    val definition: Declaration,
    val native: Declaration? = null,
) {
    data class Declaration(
        val annotationTypes: List<String>,
        val providerInterface: String? = null,
    ) {
        init {
            require(annotationTypes.none(String::isBlank)) { "Annotation types must not be blank" }
            require(providerInterface?.isNotBlank() != false) { "Provider interface must not be blank" }
        }

        val isProvider: Boolean
            get() = providerInterface != null
    }

    fun declaration(nativeBuild: Boolean): Declaration =
        if (nativeBuild) {
            requireNotNull(native) { "Native generation requires a runtime provider declaration" }
        } else {
            definition
        }

    companion object {
        val Plain =
            Declaration(
                annotationTypes = listOf("dev.museframework.dsl.annotation.MuseConfig"),
            ).let { declaration ->
                ConfigurationClassSpec(
                    definition = declaration,
                    native = declaration,
                )
            }
    }
}
