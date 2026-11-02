package dev.museframework.objectstore.api.config

import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.model.component.Processor
import dev.museframework.dsl.api.model.config.MuseConfiguration
import java.time.Duration
import java.util.UUID

sealed interface ObjectStoreRef {
    data object Default : ObjectStoreRef

    data class ByName(val name: String) : ObjectStoreRef {
        init {
            require(name.isNotBlank()) { "Object Store reference name must not be blank" }
        }
    }

    data class ByReference(val config: ObjectStoreConfig) : ObjectStoreRef
}

sealed interface ObjectStoreConnectorRef {
    data object Default : ObjectStoreConnectorRef

    data class ByName(val name: String) : ObjectStoreConnectorRef {
        init {
            require(name.isNotBlank()) { "Object Store connector reference name must not be blank" }
        }
    }

    data class ByReference(val config: ObjectStoreConnectorConfig) : ObjectStoreConnectorRef
}

data class ObjectStoreConnectorConfig(
    val name: String,
    val lockTimeout: Duration? = null,
    override val docName: String = name,
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
) : MuseConfiguration {
    init {
        require(name.isNotBlank()) { "Object Store connector name must not be blank" }
        require(lockTimeout == null || !lockTimeout.isNegative) { "lockTimeout must not be negative" }
        validateDuration(lockTimeout, "lockTimeout")
    }
}

data class ObjectStoreConfig(
    val name: String,
    val connector: ObjectStoreConnectorRef = ObjectStoreConnectorRef.Default,
    val persistent: Boolean = true,
    val maxEntries: Int? = null,
    val entryTtl: Duration? = null,
    val expirationInterval: Duration = Duration.ofMinutes(1),
    override val docName: String = name,
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
) : MuseConfiguration {
    init {
        require(name.isNotBlank()) { "Object Store name must not be blank" }
        require(maxEntries == null || maxEntries > 0) { "maxEntries must be positive" }
        validateDuration(entryTtl, "entryTtl")
        validateDuration(expirationInterval, "expirationInterval")
    }
}

sealed interface ObjectStoreOperationConfig : Processor {
    val objectStore: ObjectStoreRef
}

data class ObjectStoreClearConfig(
    override val objectStore: ObjectStoreRef = ObjectStoreRef.Default,
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
) : ObjectStoreOperationConfig

data class ObjectStoreContainsConfig(
    val key: String,
    val targetConfig: TargetConfig? = null,
    override val objectStore: ObjectStoreRef = ObjectStoreRef.Default,
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
) : ObjectStoreOperationConfig {
    init {
        require(key.isNotEmpty()) { "Object Store key must not be empty" }
        require(targetConfig == null || targetConfig.target.isNotBlank()) { "target must not be blank" }
    }
}

data class ObjectStoreRemoveConfig(
    val key: String,
    override val objectStore: ObjectStoreRef = ObjectStoreRef.Default,
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
) : ObjectStoreOperationConfig {
    init {
        require(key.isNotEmpty()) { "Object Store key must not be empty" }
    }
}

data class ObjectStoreRetrieveConfig(
    val key: String,
    val defaultValue: String? = null,
    val targetConfig: TargetConfig? = null,
    override val objectStore: ObjectStoreRef = ObjectStoreRef.Default,
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
) : ObjectStoreOperationConfig {
    init {
        require(key.isNotEmpty()) { "Object Store key must not be empty" }
        require(targetConfig == null || targetConfig.target.isNotBlank()) { "target must not be blank" }
    }
}

data class ObjectStoreRetrieveAllConfig(
    val targetConfig: TargetConfig? = null,
    override val objectStore: ObjectStoreRef = ObjectStoreRef.Default,
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
) : ObjectStoreOperationConfig {
    init {
        require(targetConfig == null || targetConfig.target.isNotBlank()) { "target must not be blank" }
    }
}

data class ObjectStoreRetrieveAllKeysConfig(
    val targetConfig: TargetConfig? = null,
    override val objectStore: ObjectStoreRef = ObjectStoreRef.Default,
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
) : ObjectStoreOperationConfig {
    init {
        require(targetConfig == null || targetConfig.target.isNotBlank()) { "target must not be blank" }
    }
}

data class ObjectStoreStoreConfig(
    val key: String,
    val value: String? = null,
    val failIfPresent: Boolean = false,
    val failOnNullValue: Boolean = true,
    override val objectStore: ObjectStoreRef = ObjectStoreRef.Default,
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
) : ObjectStoreOperationConfig {
    init {
        require(key.isNotEmpty()) { "Object Store key must not be empty" }
    }
}

private fun validateDuration(duration: Duration?, name: String) {
    if (duration == null) return
    try {
        duration.toNanos()
        duration.toMillis()
    } catch (cause: ArithmeticException) {
        throw IllegalArgumentException("$name exceeds the supported duration range", cause)
    }
}
