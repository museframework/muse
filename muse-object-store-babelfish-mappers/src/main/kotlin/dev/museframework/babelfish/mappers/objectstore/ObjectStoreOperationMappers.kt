package dev.museframework.babelfish.mappers.objectstore

import dev.museframework.babelfish.api.ElementMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.objectstore.api.config.*

object ObjectStoreClearMapper : ElementMapper<ObjectStoreClearConfig> {
    override fun map(element: RawElement, ctx: MappingContext): ObjectStoreClearConfig = withLocation(element.tag, element.location, ctx) {
        element.validateShape(emptySet())
        ObjectStoreClearConfig(
            objectStore = element.storeRef(),
            docName = element.attrs["doc:name"].orEmpty(),
            docDescription = element.attrs["doc:description"],
            sourceFile = ctx.sourceFile.toString(),
            sourceLine = element.location.line,
        )
    }
}

object ObjectStoreContainsMapper : ElementMapper<ObjectStoreContainsConfig> {
    override fun map(element: RawElement, ctx: MappingContext): ObjectStoreContainsConfig = withLocation(element.tag, element.location, ctx) {
        element.validateShape(setOf("key", "target", "targetValue"))
        ObjectStoreContainsConfig(
            key = element.attrs.required("key"),
            targetConfig = element.target(),
            objectStore = element.storeRef(),
            docName = element.attrs["doc:name"].orEmpty(),
            docDescription = element.attrs["doc:description"],
            sourceFile = ctx.sourceFile.toString(),
            sourceLine = element.location.line,
        )
    }
}

object ObjectStoreRemoveMapper : ElementMapper<ObjectStoreRemoveConfig> {
    override fun map(element: RawElement, ctx: MappingContext): ObjectStoreRemoveConfig = withLocation(element.tag, element.location, ctx) {
        element.validateShape(setOf("key"))
        ObjectStoreRemoveConfig(
            key = element.attrs.required("key"),
            objectStore = element.storeRef(),
            docName = element.attrs["doc:name"].orEmpty(),
            docDescription = element.attrs["doc:description"],
            sourceFile = ctx.sourceFile.toString(),
            sourceLine = element.location.line,
        )
    }
}

object ObjectStoreRetrieveMapper : ElementMapper<ObjectStoreRetrieveConfig> {
    override fun map(element: RawElement, ctx: MappingContext): ObjectStoreRetrieveConfig = withLocation(element.tag, element.location, ctx) {
        element.validateShape(setOf("key", "default-value", "target", "targetValue"), setOf("default-value"))
        ObjectStoreRetrieveConfig(
            key = element.attrs.required("key"),
            defaultValue = element.expression("default-value"),
            targetConfig = element.target(),
            objectStore = element.storeRef(),
            docName = element.attrs["doc:name"].orEmpty(),
            docDescription = element.attrs["doc:description"],
            sourceFile = ctx.sourceFile.toString(),
            sourceLine = element.location.line,
        )
    }
}

object ObjectStoreRetrieveAllMapper : ElementMapper<ObjectStoreRetrieveAllConfig> {
    override fun map(element: RawElement, ctx: MappingContext): ObjectStoreRetrieveAllConfig = withLocation(element.tag, element.location, ctx) {
        element.validateShape(setOf("target", "targetValue"))
        ObjectStoreRetrieveAllConfig(
            targetConfig = element.target(),
            objectStore = element.storeRef(),
            docName = element.attrs["doc:name"].orEmpty(),
            docDescription = element.attrs["doc:description"],
            sourceFile = ctx.sourceFile.toString(),
            sourceLine = element.location.line,
        )
    }
}

object ObjectStoreRetrieveAllKeysMapper : ElementMapper<ObjectStoreRetrieveAllKeysConfig> {
    override fun map(element: RawElement, ctx: MappingContext): ObjectStoreRetrieveAllKeysConfig = withLocation(element.tag, element.location, ctx) {
        element.validateShape(setOf("target", "targetValue"))
        ObjectStoreRetrieveAllKeysConfig(
            targetConfig = element.target(),
            objectStore = element.storeRef(),
            docName = element.attrs["doc:name"].orEmpty(),
            docDescription = element.attrs["doc:description"],
            sourceFile = ctx.sourceFile.toString(),
            sourceLine = element.location.line,
        )
    }
}

object ObjectStoreStoreMapper : ElementMapper<ObjectStoreStoreConfig> {
    override fun map(element: RawElement, ctx: MappingContext): ObjectStoreStoreConfig = withLocation(element.tag, element.location, ctx) {
        element.validateShape(setOf("key", "value", "failIfPresent", "failOnNullValue"), setOf("value"))
        ObjectStoreStoreConfig(
            key = element.attrs.required("key"),
            value = element.expression("value"),
            failIfPresent = element.attrs.boolean("failIfPresent", false),
            failOnNullValue = element.attrs.boolean("failOnNullValue", true),
            objectStore = element.storeRef(),
            docName = element.attrs["doc:name"].orEmpty(),
            docDescription = element.attrs["doc:description"],
            sourceFile = ctx.sourceFile.toString(),
            sourceLine = element.location.line,
        )
    }
}

object ObjectStoreClearCodeEmitter : ElementCodeEmitter<ObjectStoreClearConfig> {
    override fun emit(element: ObjectStoreClearConfig, ctx: CodegenContext): String = emitOperation("objectStoreClear", element, ctx)
}

object ObjectStoreContainsCodeEmitter : ElementCodeEmitter<ObjectStoreContainsConfig> {
    override fun emit(element: ObjectStoreContainsConfig, ctx: CodegenContext): String =
        emitOperation("objectStoreContains", element, ctx, element.targetConfig) { assignment("key", element.key, ctx) }
}

object ObjectStoreRemoveCodeEmitter : ElementCodeEmitter<ObjectStoreRemoveConfig> {
    override fun emit(element: ObjectStoreRemoveConfig, ctx: CodegenContext): String =
        emitOperation("objectStoreRemove", element, ctx) { assignment("key", element.key, ctx) }
}

object ObjectStoreRetrieveCodeEmitter : ElementCodeEmitter<ObjectStoreRetrieveConfig> {
    override fun emit(element: ObjectStoreRetrieveConfig, ctx: CodegenContext): String =
        emitOperation("objectStoreRetrieve", element, ctx, element.targetConfig) {
            assignment("key", element.key, ctx)
            assignment("defaultValue", element.defaultValue, ctx)
        }
}

object ObjectStoreRetrieveAllCodeEmitter : ElementCodeEmitter<ObjectStoreRetrieveAllConfig> {
    override fun emit(element: ObjectStoreRetrieveAllConfig, ctx: CodegenContext): String =
        emitOperation("objectStoreRetrieveAll", element, ctx, element.targetConfig)
}

object ObjectStoreRetrieveAllKeysCodeEmitter : ElementCodeEmitter<ObjectStoreRetrieveAllKeysConfig> {
    override fun emit(element: ObjectStoreRetrieveAllKeysConfig, ctx: CodegenContext): String =
        emitOperation("objectStoreRetrieveAllKeys", element, ctx, element.targetConfig)
}

object ObjectStoreStoreCodeEmitter : ElementCodeEmitter<ObjectStoreStoreConfig> {
    override fun emit(element: ObjectStoreStoreConfig, ctx: CodegenContext): String =
        emitOperation("objectStoreStore", element, ctx) {
            assignment("key", element.key, ctx)
            assignment("value", element.value, ctx)
            if (element.failIfPresent) appendLine("    failIfPresent = true")
            if (!element.failOnNullValue) appendLine("    failOnNullValue = false")
        }
}

private fun emitOperation(
    dslName: String,
    element: ObjectStoreOperationConfig,
    ctx: CodegenContext,
    target: TargetConfig? = null,
    fields: StringBuilder.() -> Unit = {},
): String {
    ctx.addImport("dev.museframework.objectstore.api.dsl.$dslName")
    return buildString {
        appendLine("$dslName(${element.objectStore.expression(ctx)}) {")
        if (element.docName.isNotEmpty()) assignment("docName", element.docName, ctx)
        assignment("docDescription", element.docDescription, ctx)
        fields()
        targetAssignment(target, ctx)
        append("}")
    }
}
