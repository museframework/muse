package dev.museframework.connectors.objectstore

import dev.museframework.connectors.objectstore.internal.ObjectStoreManager
import dev.museframework.connectors.objectstore.backend.TestObjectStoreBackendProvider
import dev.museframework.dsl.api.model.config.GlobalProperty
import dev.museframework.dsl.api.model.config.MuseConfiguration
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.forEach
import dev.museframework.engine.api.spi.MuseComponentRegistrar
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.app.MuseApplicationConfig
import dev.museframework.engine.app.bootstrap
import dev.museframework.engine.factory.MuseComponentFactory
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.PipelineElement
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.objectstore.api.config.ObjectStoreConfig
import dev.museframework.objectstore.api.config.ObjectStoreConnectorConfig
import dev.museframework.objectstore.api.config.ObjectStoreConnectorRef
import dev.museframework.objectstore.api.config.ObjectStoreRef
import dev.museframework.objectstore.api.config.ObjectStoreContainsConfig
import dev.museframework.objectstore.api.config.ObjectStoreOperationConfig
import dev.museframework.objectstore.api.config.ObjectStoreRetrieveConfig
import dev.museframework.objectstore.api.config.ObjectStoreStoreConfig
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import dev.museframework.engine.api.event.TypedValue
import java.util.ServiceLoader
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ObjectStoreConnectorRegistrarTest {
    @TempDir lateinit var dataDirectory: Path

    @Test
    fun `runtime service metadata discovers one object store registrar`() {
        val registrars = ServiceLoader.load(MuseComponentRegistrar::class.java)
            .filterIsInstance<ObjectStoreConnectorRegistrar>()
            .toList()
        assertEquals(1, registrars.size)
    }

    @Test
    fun `unknown selected backend fails during bootstrap`() = runTest {
        val failure = assertFailsWith<IllegalArgumentException> {
            val app = bootstrap(configs = listOf(
                GlobalProperty(docName = "backend", name = "muse.object-store.backend", value = "missing-provider"),
                ObjectStoreConfig("named", persistent = false),
            ))
            app.stop()
        }
        assertContains(failure.message.orEmpty(), "missing-provider")
    }

    @Test
    fun `selected provider opens isolated default and named stores without local H2 files`() = runTest {
        TestObjectStoreBackendProvider.reset()
        val app = bootstrap(configs = listOf(
            ObjectStoreConfig("named", persistent = false),
            GlobalProperty(docName = "backend", name = "muse.object-store.backend", value = "test-memory"),
        ))
        try {
            val scope = this
            suspend fun execute(component: ObjectStoreOperationConfig, payload: String): ProcessorResult {
                val processor = (app.context.factory.createProcessor(component, app.context) as PipelineElement).processor
                val context = ExecutionContext(
                    event = MuseEvent(payload = TypedValue.of(payload as Any)),
                    errorHandler = null,
                    coroutineScope = scope,
                    expressionEvaluator = app.context.expressionEvaluator,
                )
                return processor.process(context)
            }

            assertIs<ProcessorResult.Success>(execute(ObjectStoreStoreConfig("key"), "default"))
            assertIs<ProcessorResult.Success>(execute(ObjectStoreStoreConfig("key", objectStore = ObjectStoreRef.ByName("named")), "named"))
            assertEquals("default", assertIs<ProcessorResult.Success>(execute(ObjectStoreRetrieveConfig("key"), "unused")).context.event.payload?.value)
            assertEquals("named", assertIs<ProcessorResult.Success>(execute(ObjectStoreRetrieveConfig("key", objectStore = ObjectStoreRef.ByName("named")), "unused")).context.event.payload?.value)
            assertEquals(listOf("default", "named:named"), TestObjectStoreBackendProvider.opened.map { it.identity })
            assertEquals(false, Files.walk(dataDirectory).use { paths -> paths.anyMatch { it.fileName.toString().endsWith(".mv.db") } })
        } finally {
            app.stop()
        }
        assertEquals(true, TestObjectStoreBackendProvider.opened.all { it.closed })
    }

    @Test
    fun `duplicate provider IDs fail rather than selecting the first provider`() = runTest {
        val failure = assertFailsWith<IllegalArgumentException> {
            val app = bootstrap(configs = listOf(
                GlobalProperty(docName = "backend", name = "muse.object-store.backend", value = "test-duplicate"),
                ObjectStoreConfig("named", persistent = false),
            ))
            app.stop()
        }
        assertContains(failure.message.orEmpty(), "test-duplicate")
    }

    @Test
    fun `provider failure while opening a named store closes the default store`() = runTest {
        TestObjectStoreBackendProvider.reset()
        TestObjectStoreBackendProvider.failOnIdentity = "named:named"
        try {
            val failure = assertFailsWith<IllegalStateException> {
                val app = bootstrap(configs = listOf(
                    GlobalProperty(docName = "backend", name = "muse.object-store.backend", value = "test-memory"),
                    ObjectStoreConfig("named", persistent = false),
                ))
                app.stop()
            }
            assertContains(failure.message.orEmpty(), "named:named")
            assertEquals(listOf("default"), TestObjectStoreBackendProvider.opened.map { it.identity })
            assertEquals(true, TestObjectStoreBackendProvider.opened.single().closed)
        } finally {
            TestObjectStoreBackendProvider.reset()
        }
    }

    @Test
    fun `store declared before its connector resolves from complete bootstrap configuration`() = runTest {
        val app = bootstrap(configs = listOf(
            ObjectStoreConfig("named", connector = ObjectStoreConnectorRef.ByName("local"), persistent = false),
            ObjectStoreConnectorConfig("local"),
        ))
        app.stop()
    }

    @Test
    fun `duplicate store and connector names fail even with distinct document names`() {
        val duplicateStore = assertFailsWith<IllegalArgumentException> {
            bootstrap(configs = listOf(
                ObjectStoreConfig("same", persistent = false, docName = "first"),
                ObjectStoreConfig("same", persistent = false, docName = "second"),
            ))
        }
        assertContains(duplicateStore.message.orEmpty(), "Duplicate Object Store")

        val duplicateConnector = assertFailsWith<IllegalArgumentException> {
            bootstrap(configs = listOf(
                ObjectStoreConnectorConfig("same", docName = "first"),
                ObjectStoreConnectorConfig("same", docName = "second"),
            ))
        }
        assertContains(duplicateConnector.message.orEmpty(), "Duplicate Object Store connector")
    }

    @Test
    fun `distinct stores may share a documentation label without losing either store`() = runTest {
        val app = bootstrap(configs = listOf(
            ObjectStoreConfig("first", persistent = false, docName = "shared-label"),
            ObjectStoreConfig("second", persistent = false, docName = "shared-label"),
        ))
        try {
            val manager = app.context.properties["muse.object-store.internal.manager"] as ObjectStoreManager
            manager.resolve(ObjectStoreRef.ByName("first"))
            manager.resolve(ObjectStoreRef.ByName("second"))
        } finally {
            app.stop()
        }
    }

    @Test
    fun `missing connector fails bootstrap`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            bootstrap(configs = listOf(
                ObjectStoreConfig("named", persistent = false, connector = ObjectStoreConnectorRef.ByName("missing")),
            ))
        }
        assertContains(failure.message.orEmpty(), "missing")
    }

    @Test
    fun `missing named store in a nested flow fails bootstrap before init`() {
        val nested = flow("nested-object-store") {
            forEach {
                processor(ObjectStoreContainsConfig("key", objectStore = ObjectStoreRef.ByName("missing")))
            }
        }
        val failure = assertFailsWith<IllegalArgumentException> {
            bootstrap(
                MuseApplicationConfig(
                    flows = listOf(nested),
                    configs = listOf(GlobalProperty(docName = "object-store-data-directory", name = "muse.object-store.data-dir", value = dataDirectory.toString())),
                    factory = factory(),
                ),
            )
        }
        assertContains(failure.message.orEmpty(), "missing")
    }

    @Test
    fun `implicit default store opens during bootstrap when an operation uses it`() = runTest {
        val operation = flow("implicit-object-store") { processor(ObjectStoreContainsConfig("key")) }
        val app = bootstrap(
            MuseApplicationConfig(
                flows = listOf(operation),
                configs = listOf(GlobalProperty(docName = "object-store-data-directory", name = "muse.object-store.data-dir", value = dataDirectory.toString())),
                factory = factory(),
            ),
        )
        try {
            val manager = app.context.properties["muse.object-store.internal.manager"] as ObjectStoreManager
            manager.resolve(ObjectStoreRef.Default).contains("key")
        } finally {
            app.stop()
        }
    }

    @Test
    fun `later bootstrap failure closes an already opened store manager`() {
        var opened: ObjectStoreManager? = null
        val factory = factory().apply {
            registerConfig(FailingConfig::class) { _, context ->
                opened = context.properties["muse.object-store.internal.manager"] as ObjectStoreManager
                throw IllegalStateException("later configuration failed")
            }
        }
        assertFailsWith<IllegalStateException> {
            bootstrap(configs = listOf(ObjectStoreConfig("named", persistent = false), FailingConfig()), factory = factory)
        }
        assertFailsWith<IllegalStateException> { opened!!.resolve(ObjectStoreRef.ByName("named")).contains("key") }
    }

    private fun bootstrap(configs: List<MuseConfiguration>, factory: MuseComponentFactory = factory()) =
        bootstrap(
            MuseApplicationConfig(
                flows = emptyList(),
                configs = listOf(
                    GlobalProperty(docName = "object-store-data-directory", name = "muse.object-store.data-dir", value = dataDirectory.toString()),
                ) + configs,
                factory = factory,
            ),
        )

    private fun factory() = MuseComponentFactory().apply {
        registerConfig(GlobalProperty::class) { property, context -> context.properties.setProperty(property.name, property.value) }
        ObjectStoreConnectorRegistrar().register(this)
    }

    private data class FailingConfig(
        override val docName: String = "failing",
        override val docId: String = "failing-id",
        override val docDescription: String? = null,
        override val sourceFile: String? = null,
        override val sourceLine: Int? = null,
        override val sourceClass: String? = null,
    ) : MuseConfiguration
}
