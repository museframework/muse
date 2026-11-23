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
    implementationClass: String = value!!::class.java.name,
) {
    val incoming =
        OwnedRegistration(
            value = value,
            owner = owner,
            implementationClass = implementationClass,
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
