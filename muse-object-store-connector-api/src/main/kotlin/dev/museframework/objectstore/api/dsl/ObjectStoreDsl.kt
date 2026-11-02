package dev.museframework.objectstore.api.dsl

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.dsl.PipelineOwner
import dev.museframework.dsl.api.util.CallSiteCapture
import dev.museframework.objectstore.api.config.*
import java.time.Duration
import java.util.UUID

@MuseDsl
abstract class ObjectStoreComponentBuilder protected constructor() {
    var docName: String = ""
    var docId: String = UUID.randomUUID().toString()
    var docDescription: String? = null
    var sourceFile: String? = null
    var sourceLine: Int? = null
    var sourceClass: String? = null

    protected fun sourceLocation(): CallSiteCapture.CallSite {
        val captured = CallSiteCapture.capture()
        return CallSiteCapture.CallSite(
            sourceFile = sourceFile ?: captured.sourceFile,
            sourceLine = sourceLine ?: captured.sourceLine,
            sourceClass = sourceClass ?: captured.sourceClass,
        )
    }
}

@MuseDsl
class ObjectStoreConnectorBuilder internal constructor() : ObjectStoreComponentBuilder() {
    var name: String = ""
    var lockTimeout: Duration? = null

    fun build(): ObjectStoreConnectorConfig {
        val location = sourceLocation()
        return ObjectStoreConnectorConfig(
            name = name,
            lockTimeout = lockTimeout,
            docName = docName.ifBlank { name },
            docId = docId,
            docDescription = docDescription,
            sourceFile = location.sourceFile,
            sourceLine = location.sourceLine,
            sourceClass = location.sourceClass,
        )
    }
}

fun objectStoreConnector(block: ObjectStoreConnectorBuilder.() -> Unit): ObjectStoreConnectorConfig =
    ObjectStoreConnectorBuilder().apply(block).build()

fun objectStoreConnector(name: String): ObjectStoreConnectorRef = ObjectStoreConnectorRef.ByName(name)

fun objectStoreConnector(config: ObjectStoreConnectorConfig): ObjectStoreConnectorRef = ObjectStoreConnectorRef.ByReference(config)

@MuseDsl
class ObjectStoreBuilder internal constructor() : ObjectStoreComponentBuilder() {
    var name: String = ""
    var connector: ObjectStoreConnectorRef = ObjectStoreConnectorRef.Default
    var persistent: Boolean = true
    var maxEntries: Int? = null
    var entryTtl: Duration? = null
    var expirationInterval: Duration = Duration.ofMinutes(1)

    fun build(): ObjectStoreConfig {
        val location = sourceLocation()
        return ObjectStoreConfig(
            name = name,
            connector = connector,
            persistent = persistent,
            maxEntries = maxEntries,
            entryTtl = entryTtl,
            expirationInterval = expirationInterval,
            docName = docName.ifBlank { name },
            docId = docId,
            docDescription = docDescription,
            sourceFile = location.sourceFile,
            sourceLine = location.sourceLine,
            sourceClass = location.sourceClass,
        )
    }
}

fun objectStore(block: ObjectStoreBuilder.() -> Unit): ObjectStoreConfig =
    ObjectStoreBuilder().apply(block).build()

fun objectStore(name: String): ObjectStoreRef = ObjectStoreRef.ByName(name)

fun objectStore(config: ObjectStoreConfig): ObjectStoreRef = ObjectStoreRef.ByReference(config)

@MuseDsl
class ObjectStoreClearBuilder internal constructor(
    private val objectStore: ObjectStoreRef,
) : ObjectStoreComponentBuilder() {

    fun build(): ObjectStoreClearConfig {
        val location = sourceLocation()
        return ObjectStoreClearConfig(
            objectStore = objectStore,
            docName = docName,
            docId = docId,
            docDescription = docDescription,
            sourceFile = location.sourceFile,
            sourceLine = location.sourceLine,
            sourceClass = location.sourceClass,
        )
    }
}

fun PipelineOwner.objectStoreClear(
    objectStore: ObjectStoreRef = ObjectStoreRef.Default,
    block: ObjectStoreClearBuilder.() -> Unit = {},
) {
    processor(ObjectStoreClearBuilder(objectStore).apply(block).build())
}

fun PipelineOwner.objectStoreClear(
    objectStore: ObjectStoreConfig,
    block: ObjectStoreClearBuilder.() -> Unit = {},
) = objectStoreClear(ObjectStoreRef.ByReference(objectStore), block)

@MuseDsl
class ObjectStoreContainsBuilder internal constructor(
    private val objectStore: ObjectStoreRef,
) : ObjectStoreComponentBuilder() {
    var key: String = ""
    var targetConfig: TargetConfig? = null

    fun build(): ObjectStoreContainsConfig {
        val location = sourceLocation()
        return ObjectStoreContainsConfig(
            objectStore = objectStore,
            key = key,
            targetConfig = targetConfig,
            docName = docName,
            docId = docId,
            docDescription = docDescription,
            sourceFile = location.sourceFile,
            sourceLine = location.sourceLine,
            sourceClass = location.sourceClass,
        )
    }
}

fun PipelineOwner.objectStoreContains(
    objectStore: ObjectStoreRef = ObjectStoreRef.Default,
    block: ObjectStoreContainsBuilder.() -> Unit = {},
) {
    processor(ObjectStoreContainsBuilder(objectStore).apply(block).build())
}

fun PipelineOwner.objectStoreContains(
    objectStore: ObjectStoreConfig,
    block: ObjectStoreContainsBuilder.() -> Unit = {},
) = objectStoreContains(ObjectStoreRef.ByReference(objectStore), block)

@MuseDsl
class ObjectStoreRemoveBuilder internal constructor(
    private val objectStore: ObjectStoreRef,
) : ObjectStoreComponentBuilder() {
    var key: String = ""

    fun build(): ObjectStoreRemoveConfig {
        val location = sourceLocation()
        return ObjectStoreRemoveConfig(
            objectStore = objectStore,
            key = key,
            docName = docName,
            docId = docId,
            docDescription = docDescription,
            sourceFile = location.sourceFile,
            sourceLine = location.sourceLine,
            sourceClass = location.sourceClass,
        )
    }
}

fun PipelineOwner.objectStoreRemove(
    objectStore: ObjectStoreRef = ObjectStoreRef.Default,
    block: ObjectStoreRemoveBuilder.() -> Unit = {},
) {
    processor(ObjectStoreRemoveBuilder(objectStore).apply(block).build())
}

fun PipelineOwner.objectStoreRemove(
    objectStore: ObjectStoreConfig,
    block: ObjectStoreRemoveBuilder.() -> Unit = {},
) = objectStoreRemove(ObjectStoreRef.ByReference(objectStore), block)

@MuseDsl
class ObjectStoreRetrieveBuilder internal constructor(
    private val objectStore: ObjectStoreRef,
) : ObjectStoreComponentBuilder() {
    var key: String = ""
    var defaultValue: String? = null
    var targetConfig: TargetConfig? = null

    fun build(): ObjectStoreRetrieveConfig {
        val location = sourceLocation()
        return ObjectStoreRetrieveConfig(
            objectStore = objectStore,
            key = key,
            defaultValue = defaultValue,
            targetConfig = targetConfig,
            docName = docName,
            docId = docId,
            docDescription = docDescription,
            sourceFile = location.sourceFile,
            sourceLine = location.sourceLine,
            sourceClass = location.sourceClass,
        )
    }
}

fun PipelineOwner.objectStoreRetrieve(
    objectStore: ObjectStoreRef = ObjectStoreRef.Default,
    block: ObjectStoreRetrieveBuilder.() -> Unit = {},
) {
    processor(ObjectStoreRetrieveBuilder(objectStore).apply(block).build())
}

fun PipelineOwner.objectStoreRetrieve(
    objectStore: ObjectStoreConfig,
    block: ObjectStoreRetrieveBuilder.() -> Unit = {},
) = objectStoreRetrieve(ObjectStoreRef.ByReference(objectStore), block)

@MuseDsl
class ObjectStoreRetrieveAllBuilder internal constructor(
    private val objectStore: ObjectStoreRef,
) : ObjectStoreComponentBuilder() {
    var targetConfig: TargetConfig? = null

    fun build(): ObjectStoreRetrieveAllConfig {
        val location = sourceLocation()
        return ObjectStoreRetrieveAllConfig(
            objectStore = objectStore,
            targetConfig = targetConfig,
            docName = docName,
            docId = docId,
            docDescription = docDescription,
            sourceFile = location.sourceFile,
            sourceLine = location.sourceLine,
            sourceClass = location.sourceClass,
        )
    }
}

fun PipelineOwner.objectStoreRetrieveAll(
    objectStore: ObjectStoreRef = ObjectStoreRef.Default,
    block: ObjectStoreRetrieveAllBuilder.() -> Unit = {},
) {
    processor(ObjectStoreRetrieveAllBuilder(objectStore).apply(block).build())
}

fun PipelineOwner.objectStoreRetrieveAll(
    objectStore: ObjectStoreConfig,
    block: ObjectStoreRetrieveAllBuilder.() -> Unit = {},
) = objectStoreRetrieveAll(ObjectStoreRef.ByReference(objectStore), block)

@MuseDsl
class ObjectStoreRetrieveAllKeysBuilder internal constructor(
    private val objectStore: ObjectStoreRef,
) : ObjectStoreComponentBuilder() {
    var targetConfig: TargetConfig? = null

    fun build(): ObjectStoreRetrieveAllKeysConfig {
        val location = sourceLocation()
        return ObjectStoreRetrieveAllKeysConfig(
            objectStore = objectStore,
            targetConfig = targetConfig,
            docName = docName,
            docId = docId,
            docDescription = docDescription,
            sourceFile = location.sourceFile,
            sourceLine = location.sourceLine,
            sourceClass = location.sourceClass,
        )
    }
}

fun PipelineOwner.objectStoreRetrieveAllKeys(
    objectStore: ObjectStoreRef = ObjectStoreRef.Default,
    block: ObjectStoreRetrieveAllKeysBuilder.() -> Unit = {},
) {
    processor(ObjectStoreRetrieveAllKeysBuilder(objectStore).apply(block).build())
}

fun PipelineOwner.objectStoreRetrieveAllKeys(
    objectStore: ObjectStoreConfig,
    block: ObjectStoreRetrieveAllKeysBuilder.() -> Unit = {},
) = objectStoreRetrieveAllKeys(ObjectStoreRef.ByReference(objectStore), block)

@MuseDsl
class ObjectStoreStoreBuilder internal constructor(
    private val objectStore: ObjectStoreRef,
) : ObjectStoreComponentBuilder() {
    var key: String = ""
    var value: String? = null
    var failIfPresent: Boolean = false
    var failOnNullValue: Boolean = true

    fun build(): ObjectStoreStoreConfig {
        val location = sourceLocation()
        return ObjectStoreStoreConfig(
            objectStore = objectStore,
            key = key,
            value = value,
            failIfPresent = failIfPresent,
            failOnNullValue = failOnNullValue,
            docName = docName,
            docId = docId,
            docDescription = docDescription,
            sourceFile = location.sourceFile,
            sourceLine = location.sourceLine,
            sourceClass = location.sourceClass,
        )
    }
}

fun PipelineOwner.objectStoreStore(
    objectStore: ObjectStoreRef = ObjectStoreRef.Default,
    block: ObjectStoreStoreBuilder.() -> Unit = {},
) {
    processor(ObjectStoreStoreBuilder(objectStore).apply(block).build())
}

fun PipelineOwner.objectStoreStore(
    objectStore: ObjectStoreConfig,
    block: ObjectStoreStoreBuilder.() -> Unit = {},
) = objectStoreStore(ObjectStoreRef.ByReference(objectStore), block)
