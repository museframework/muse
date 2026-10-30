package dev.museframework.babelfish.spi

import dev.museframework.babelfish.exception.BabelfishRegistrationCollisionException

internal data class OwnedRegistration<T>(
    val value: T,
    val owner: BabelfishRegistrationOwner,
    val implementationClass: String,
)

internal fun <K, T> MutableMap<K, OwnedRegistration<T>>.registerIfAbsent(
    key: K,
    displayKey: String,
    category: String,
    value: T,
    owner: BabelfishRegistrationOwner,
) {
    val incoming =
        OwnedRegistration(
            value = value,
            owner = owner,
            implementationClass = value!!::class.java.name,
        )
    val existing = putIfAbsent(key, incoming) ?: return

    throw BabelfishRegistrationCollisionException(
        category = category,
        key = displayKey,
        existingOwner = existing.owner,
        existingRegistrationClass = existing.implementationClass,
        incomingOwner = incoming.owner,
        incomingRegistrationClass = incoming.implementationClass,
    )
}
