package dev.museframework.babelfish.spi

/**
 * Identifies the provider that owns registrations performed in one scope.
 *
 * Direct registrar calls remain supported and use [DIRECT] unless a caller
 * explicitly enters a provider-owned registration scope.
 */
data class BabelfishRegistrationOwner(
    val id: String,
    val implementationClass: String,
) {
    companion object {
        val DIRECT =
            BabelfishRegistrationOwner(
                id = "direct",
                implementationClass = "direct-registration",
            )
    }
}
