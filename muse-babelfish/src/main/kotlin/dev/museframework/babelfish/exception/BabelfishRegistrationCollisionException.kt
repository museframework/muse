package dev.museframework.babelfish.exception

import dev.museframework.babelfish.spi.BabelfishRegistrationOwner

/**
 * Raised when a mapper or emitter registration attempts to replace an
 * existing registration in the same category.
 */
class BabelfishRegistrationCollisionException(
    val category: String,
    val key: String,
    val existingOwner: BabelfishRegistrationOwner,
    val existingRegistrationClass: String,
    val incomingOwner: BabelfishRegistrationOwner,
    val incomingRegistrationClass: String,
) : IllegalStateException(
        "Babelfish registration collision for $category key '$key': " +
            "existing owner id='${existingOwner.id}', " +
            "class='${existingOwner.implementationClass}', " +
            "registration class='$existingRegistrationClass'; " +
            "incoming owner id='${incomingOwner.id}', " +
            "class='${incomingOwner.implementationClass}', " +
            "registration class='$incomingRegistrationClass'. " +
            "Registration keys cannot be overridden.",
    )
