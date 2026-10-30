package dev.museframework.engine.integration

import dev.museframework.dsl.api.config.SetPayloadConfig
import dev.museframework.dsl.api.config.SetVariableConfig
import dev.museframework.dsl.api.model.config.ConfigurationProperties
import dev.museframework.dsl.api.model.config.EnvironmentProperties
import dev.museframework.dsl.api.model.config.GlobalProperty
import dev.museframework.dsl.api.model.config.JvmSystemProperties
import dev.museframework.dsl.api.model.config.MuseConfiguration
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.dsl.dsl.setVariable
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.components.processor.setPayloadProcessorBuilder
import dev.museframework.engine.components.processor.setVariableProcessorBuilder
import dev.museframework.engine.dataweave.DataWeaveContext
import dev.museframework.engine.dataweave.MuseDataWeaveExecutor
import dev.museframework.engine.factory.MuseComponentFactory
import dev.museframework.engine.properties.PropertiesLoader
import dev.museframework.engine.properties.PropertiesLoader.configurationPropertiesBuilder
import dev.museframework.engine.properties.PropertiesLoader.environmentPropertiesBuilder
import dev.museframework.engine.properties.PropertiesLoader.globalPropertyBuilder
import dev.museframework.engine.properties.PropertiesLoader.jvmSystemPropertiesBuilder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Properties

/**
 * Integration tests for property resolution in Muse expressions.
 *
 * Flow-level cases use [EngineFlowHarness] so they exercise real bootstrap and
 * processor behavior without coupling property assertions to a timer source.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MuseExpressionWithPropertiesIT {
    private fun defaultFactory(): MuseComponentFactory =
        MuseComponentFactory().apply {
            registerProcessor(SetPayloadConfig::class, setPayloadProcessorBuilder)
            registerProcessor(SetVariableConfig::class, setVariableProcessorBuilder)
            registerConfig(GlobalProperty::class, globalPropertyBuilder)
            registerConfig(ConfigurationProperties::class, configurationPropertiesBuilder)
            registerConfig(EnvironmentProperties::class, environmentPropertiesBuilder)
            registerConfig(JvmSystemProperties::class, jvmSystemPropertiesBuilder)
        }

    private fun defaultFactory(environment: Map<String, String>): MuseComponentFactory =
        defaultFactory().apply {
            registerConfig(EnvironmentProperties::class) { config, context ->
                PropertiesLoader.apply(config, context.properties, environment)
            }
        }

    private suspend fun TestScope.evaluate(
        flowName: String,
        expression: String,
        configs: List<MuseConfiguration> = emptyList(),
        factory: MuseComponentFactory = defaultFactory(),
    ): EngineFlowExecution =
        executeFlow(
            flow =
                flow(flowName) {
                    setPayload {
                        docName = "Evaluate property expression"
                        value = expression
                    }
                },
            configs = configs,
            factory = factory,
        )

    private suspend fun TestScope.executeFlow(
        flow: MuseFlow.Flow,
        configs: List<MuseConfiguration> = emptyList(),
        factory: MuseComponentFactory = defaultFactory(),
    ): EngineFlowExecution =
        EngineFlowHarness(
            testScope = this,
            flow = flow,
            factory = factory,
            configs = configs,
        ).executeWithContext()

    private fun global(
        name: String,
        value: String,
    ) = GlobalProperty(
        docName = name,
        docId = name,
        name = name,
        value = value,
    )

    private fun configurationFile(
        content: String,
        suffix: String = ".properties",
    ): ConfigurationProperties {
        val file = File.createTempFile("muse-properties-", suffix)
        file.writeText(content.trimIndent())
        file.deleteOnExit()
        return ConfigurationProperties(
            docName = "Test configuration file",
            docId = file.name,
            file = "file:${file.absolutePath}",
        )
    }

    private fun executor() = MuseDataWeaveExecutor()

    private fun context(
        properties: Properties = Properties(),
        payload: Any = "test",
    ) = DataWeaveContext(
        event = MuseEvent(payload = TypedValue.of(payload)),
        properties = properties,
    )

    private fun properties(vararg pairs: Pair<String, String>) =
        Properties().apply { pairs.forEach { (key, value) -> setProperty(key, value) } }

    @Test
    fun `should resolve simple property placeholder in setPayload expression`() =
        runTest {
            val result =
                evaluate(
                    flowName = "property-in-expression-flow",
                    expression = "#[p('greeting.message')]",
                    configs = listOf(global("greeting.message", "Hello from property!")),
                )

            assertEquals("Hello from property!", result.event.payload?.value)
        }

    @Test
    fun `should resolve property placeholder with nested key syntax`() =
        runTest {
            val result =
                evaluate(
                    flowName = "nested-property-flow",
                    expression = "#[p('db.host') ++ ':' ++ p('db.port')]",
                    configs =
                        listOf(
                            global("db.host", "localhost"),
                            global("db.port", "5432"),
                        ),
                )

            assertEquals("localhost:5432", result.event.payload?.value)
        }

    @Test
    fun `should resolve property placeholders within property values at bootstrap`() =
        runTest {
            val result =
                evaluate(
                    flowName = "placeholder-resolution-flow",
                    expression = "#[p('service.api.url')]",
                    configs =
                        listOf(
                            global("service.base.url", "https://api.example.com"),
                            global("service.api.url", "\${service.base.url}/v1/api"),
                        ),
                )

            assertEquals("https://api.example.com/v1/api", result.event.payload?.value)
        }

    @Test
    fun `should resolve multiple property placeholders in single value`() =
        runTest {
            val result =
                evaluate(
                    flowName = "multiple-placeholders-flow",
                    expression = "#[p('database.jdbc.url')]",
                    configs =
                        listOf(
                            global("database.host", "db.example.com"),
                            global("database.port", "3306"),
                            global("database.name", "myapp"),
                            global(
                                "database.jdbc.url",
                                "jdbc:mysql://\${database.host}:\${database.port}/\${database.name}",
                            ),
                        ),
                )

            assertEquals("jdbc:mysql://db.example.com:3306/myapp", result.event.payload?.value)
        }

    @Test
    fun `should load properties from file and use in expressions`() =
        runTest {
            val fileConfig =
                configurationFile(
                    """
                    app.name=MyApplication
                    app.version=1.0.0
                    app.description=MyApplication v1.0.0
                    """,
                )
            val result =
                evaluate(
                    flowName = "file-properties-flow",
                    expression = "#[p('app.description')]",
                    configs = listOf(fileConfig),
                )

            assertEquals("MyApplication v1.0.0", result.event.payload?.value)
        }

    @Test
    fun `should use properties in DataWeave object expressions`() =
        runTest {
            val expression =
                """
                #[%dw 2.0
                output application/java
                ---
                {
                    endpoint: p('api.endpoint'),
                    apiKey: p('api.key'),
                    active: true
                }]
                """.trimIndent().replace("\n", " ").replace("  ", " ")
            val result =
                evaluate(
                    flowName = "properties-object-flow",
                    expression = expression,
                    configs =
                        listOf(
                            global("api.key", "sk-123456789"),
                            global("api.endpoint", "https://api.example.com/v2"),
                        ),
                )

            val payload = result.event.payload?.value as? Map<*, *>
            assertNotNull(payload)
            assertEquals("https://api.example.com/v2", payload?.get("endpoint"))
            assertEquals("sk-123456789", payload?.get("apiKey"))
            assertEquals(true, payload?.get("active"))
        }

    @Test
    fun `should interpolate properties within string expressions`() =
        runTest {
            val result =
                evaluate(
                    flowName = "property-interpolation-flow",
                    expression = "#[\"Welcome, \" ++ p('user.name') ++ \"! Running in \" ++ p('app.env') ++ \" mode.\"]",
                    configs =
                        listOf(
                            global("user.name", "John Doe"),
                            global("app.env", "production"),
                        ),
                )

            assertEquals("Welcome, John Doe! Running in production mode.", result.event.payload?.value)
        }

    @Test
    fun `should set variables using property values`() =
        runTest {
            val propertyFlow =
                flow("property-in-variable-flow") {
                    setVariable("maxRetries") {
                        docName = "Set max retries"
                        value = "#[p('retry.max')]"
                    }
                    setVariable("timeoutMs") {
                        docName = "Set timeout"
                        value = "#[p('timeout.ms')]"
                    }
                    setPayload {
                        docName = "Set summary"
                        value = "#[\"Retries: \" ++ vars.maxRetries ++ \", Timeout: \" ++ vars.timeoutMs ++ \"ms\"]"
                    }
                }
            val result =
                executeFlow(
                    flow = propertyFlow,
                    configs =
                        listOf(
                            global("retry.max", "3"),
                            global("timeout.ms", "5000"),
                        ),
                )

            assertEquals("Retries: 3, Timeout: 5000ms", result.event.payload?.value)
            assertEquals("3", result.event.variables["maxRetries"]?.value)
            assertEquals("5000", result.event.variables["timeoutMs"]?.value)
        }

    @Test
    fun `should handle chained property placeholder resolution`() =
        runTest {
            val result =
                evaluate(
                    flowName = "chained-properties-flow",
                    expression = "#[p('config.level3')]",
                    configs =
                        listOf(
                            global("config.level1", "base"),
                            global("config.level2", "\${config.level1}/extended"),
                            global("config.level3", "\${config.level2}/full"),
                        ),
                )

            assertEquals("base/extended/full", result.event.payload?.value)
        }

    @Test
    fun `should handle properties with special characters and URLs`() =
        runTest {
            val result =
                evaluate(
                    flowName = "special-chars-property-flow",
                    expression = "#[p('db.connection')]",
                    configs =
                        listOf(
                            global(
                                "db.connection",
                                "postgresql://user:pass@host:5432/db?ssl=true&timeout=30",
                            ),
                        ),
                )

            assertEquals(
                "postgresql://user:pass@host:5432/db?ssl=true&timeout=30",
                result.event.payload?.value,
            )
        }

    @Test
    fun `should access properties via p function in DataWeave context`() {
        val props =
            properties(
                "app.name" to "MuseTest",
                "app.version" to "2.0.0",
            )

        val result = executor().evaluate("p('app.name')", context(props))

        assertEquals("MuseTest", result?.getOrNull())
    }

    @Test
    fun `should use properties in DataWeave object construction`() {
        val props =
            properties(
                "service.host" to "api.test.com",
                "service.port" to "8443",
            )

        val result =
            executor().evaluate(
                """
                %dw 2.0
                output application/java
                ---
                {
                    host: p('service.host'),
                    port: p('service.port') as Number,
                    url: "https://" ++ p('service.host') ++ ":" ++ p('service.port')
                }
                """.trimIndent(),
                context(props),
            )

        val map = result?.getOrNull() as? Map<*, *>
        assertNotNull(map)
        assertEquals("api.test.com", map?.get("host"))
        assertEquals(8443, (map?.get("port") as? Number)?.toInt())
        assertEquals("https://api.test.com:8443", map?.get("url"))
    }

    @Test
    fun `should combine properties with payload data`() {
        val props =
            properties(
                "default.currency" to "USD",
                "default.locale" to "en_US",
            )

        val result =
            executor().evaluate(
                """
                %dw 2.0
                output application/java
                ---
                {
                    product: payload.name,
                    price: payload.price,
                    currency: p('default.currency'),
                    locale: p('default.locale')
                }
                """.trimIndent(),
                context(props, payload = mapOf("name" to "Widget", "price" to 99.99)),
            )

        val map = result?.getOrNull() as? Map<*, *>
        assertNotNull(map)
        assertEquals("Widget", map?.get("product"))
        assertEquals(99.99, (map?.get("price") as? Number)?.toDouble())
        assertEquals("USD", map?.get("currency"))
        assertEquals("en_US", map?.get("locale"))
    }

    @Test
    fun `should handle missing property with error`() {
        val result =
            runCatching {
                executor().evaluate("p('nonexistent.property')", context())
            }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("nonexistent.property") == true)
    }

    @Test
    fun `should use file properties with nested keys in expressions`() =
        runTest {
            val fileConfig =
                configurationFile(
                    """
                    server.host=localhost
                    server.port=8080
                    app.name=TestApp
                    """,
                )
            val result =
                evaluate(
                    flowName = "file-nested-properties-flow",
                    expression = "#[p('server.host') ++ ':' ++ p('server.port')]",
                    configs = listOf(fileConfig),
                )

            assertEquals("localhost:8080", result.event.payload?.value)
        }

    @Test
    fun `should demonstrate difference between placeholder resolution and runtime p function`() =
        runTest {
            val result =
                evaluate(
                    flowName = "resolution-comparison-flow",
                    expression = "#[\"Static: \" ++ p('config.static') ++ \", Composed: \" ++ p('config.composed')]",
                    configs =
                        listOf(
                            global("config.static", "static-value"),
                            global("config.composed", "composed-\${config.static}"),
                        ),
                )

            assertEquals(
                "Static: static-value, Composed: composed-static-value",
                result.event.payload?.value,
            )
        }

    @Test
    fun `should load flat properties from YAML file`() =
        runTest {
            val fileConfig =
                configurationFile(
                    content =
                        """
                        features:
                          feature1: enabled
                          feature2: disabled
                        api:
                          version: v2
                          rateLimit: 1000
                        """,
                    suffix = ".yaml",
                )
            val result =
                evaluate(
                    flowName = "yaml-features-flow",
                    expression = "#[\"API v\" ++ p('api.version') ++ \" - Rate: \" ++ p('api.rateLimit')]",
                    configs = listOf(fileConfig),
                )

            assertEquals("API vv2 - Rate: 1000", result.event.payload?.value)
        }

    @Test
    fun `should use environment variable properties in expressions`() =
        runTest {
            val result =
                evaluate(
                    flowName = "env-vars-flow",
                    expression = "#[\"User: \" ++ p('env.USER') ++ \" at \" ++ p('env.HOME')]",
                    configs =
                        listOf(
                            global("env.HOME", "/home/user"),
                            global("env.PATH", "/usr/local/bin:/usr/bin:/bin"),
                            global("env.USER", "testuser"),
                        ),
                )

            assertEquals("User: testuser at /home/user", result.event.payload?.value)
            assertEquals("/usr/local/bin:/usr/bin:/bin", result.properties.getProperty("env.PATH"))
        }

    @Test
    fun `should combine environment variables with other properties`() =
        runTest {
            val result =
                evaluate(
                    flowName = "env-combined-flow",
                    expression = "#[\"App: \" ++ p('app.full.name') ++ \" running in \" ++ p('STAGE')]",
                    configs =
                        listOf(
                            global("STAGE", "production"),
                            global("app.name", "MyApp"),
                            global("app.full.name", "\${app.name}-\${STAGE}"),
                        ),
                )

            assertEquals("App: MyApp-production running in production", result.event.payload?.value)
        }

    @Test
    fun `should use certificate from environment variable like Kubernetes Secret`() =
        runTest {
            val certificatePem =
                """
                -----BEGIN CERTIFICATE-----
                MIIDXTCCAkWgAwIBAgIJAJC1HiIAZAiUMA0GCSqGSIb3Qa3BajELMAkGA1UEBhMC
                U0cxDzANBgNVBAgTBlNpbmdhcG9yZTEPMA0GA1UEBxMGU2luZ2Fwb3JlMRMwEQYD
                VQQKEwpBY21lIEluYy4xEjAQBgNVBAsTCVByb2R1Y3RzMRMwEQYDVQQDEwpBY21l
                IEluYy4wHhcNMTcwNjE0MDIxNDEyWhcNMjcwNjEyMDIxNDEyWjBhMQswCQYDVQQG
                -----END CERTIFICATE-----
                """.trimIndent()
            val result =
                evaluate(
                    flowName = "k8s-secret-cert-flow",
                    expression = "#[\"Cert path: \" ++ p('CERT_PATH') ++ \", Key path: \" ++ p('TLS_KEY')]",
                    configs =
                        listOf(
                            global("TLS_CERT", certificatePem),
                            global("TLS_KEY", "/etc/ssl/private/server.key"),
                            global("CERT_PATH", "/etc/ssl/certs/server.crt"),
                        ),
                )

            assertEquals(
                "Cert path: /etc/ssl/certs/server.crt, Key path: /etc/ssl/private/server.key",
                result.event.payload?.value,
            )
            val storedCertificate = result.properties.getProperty("TLS_CERT")
            assertTrue(storedCertificate.contains("BEGIN CERTIFICATE"))
            assertTrue(storedCertificate.contains("END CERTIFICATE"))
        }

    @Test
    fun `should use JSON configuration from environment variable like Kubernetes ConfigMap`() =
        runTest {
            val jsonConfig =
                """{"database":{"host":"postgres.db.svc.cluster.local","port":5432,"ssl":true},"cache":{"enabled":true,"ttl":300}}"""
            val result =
                evaluate(
                    flowName = "k8s-configmap-json-flow",
                    expression = "#[\"Features: \" ++ p('FEATURE_FLAGS')]",
                    configs =
                        listOf(
                            global("APP_CONFIG_JSON", jsonConfig),
                            global("FEATURE_FLAGS", """{"darkMode":true,"betaFeatures":false,"analytics":true}"""),
                        ),
                )

            val payload = result.event.payload?.value.toString()
            assertTrue(payload.contains("darkMode"))
            assertTrue(payload.contains("analytics"))
            val storedJson = result.properties.getProperty("APP_CONFIG_JSON")
            assertTrue(storedJson.contains("postgres.db.svc.cluster.local"))
            assertTrue(storedJson.contains("\"ssl\":true"))
        }

    @Test
    fun `should use multi-line file content from environment variable`() =
        runTest {
            val nginxConfig =
                """
                server {
                    listen 443 ssl;
                    server_name api.example.com;
                    ssl_certificate /etc/ssl/certs/server.crt;
                    ssl_certificate_key /etc/ssl/private/server.key;
                    location / {
                        proxy_pass http://backend:8080;
                    }
                }
                """.trimIndent()
            val result =
                evaluate(
                    flowName = "k8s-multiline-config-flow",
                    expression =
                        "#[\"Log level: \" ++ p('LOG_LEVEL') ++ \", Config lines: \" ++ sizeOf(p('NGINX_CONFIG') splitBy \"\\n\")]",
                    configs =
                        listOf(
                            global("NGINX_CONFIG", nginxConfig),
                            global("LOG_LEVEL", "INFO"),
                        ),
                )

            assertEquals("Log level: INFO, Config lines: 9", result.event.payload?.value)
            val storedConfig = result.properties.getProperty("NGINX_CONFIG")
            assertTrue(storedConfig.contains("listen 443 ssl"))
            assertTrue(storedConfig.contains("proxy_pass"))
        }

    @Test
    fun `should use volume mount paths from environment variables`() =
        runTest {
            val result =
                evaluate(
                    flowName = "k8s-volume-paths-flow",
                    expression =
                        "#[\"Config: \" ++ p('CONFIG_VOLUME_PATH') ++ \", Data: \" ++ p('DATA_VOLUME_PATH') ++ \", Secrets: \" ++ p('SECRETS_VOLUME_PATH')]",
                    configs =
                        listOf(
                            global("CONFIG_VOLUME_PATH", "/etc/myapp/config"),
                            global("DATA_VOLUME_PATH", "/var/lib/myapp/data"),
                            global("SECRETS_VOLUME_PATH", "/etc/myapp/secrets"),
                            global("TEMP_VOLUME_PATH", "/tmp/myapp"),
                        ),
                )

            assertEquals(
                "Config: /etc/myapp/config, Data: /var/lib/myapp/data, Secrets: /etc/myapp/secrets",
                result.event.payload?.value,
            )
            assertEquals("/tmp/myapp", result.properties.getProperty("TEMP_VOLUME_PATH"))
        }

    @Test
    fun `should use database connection string from Kubernetes Secret env var`() =
        runTest {
            val result =
                evaluate(
                    flowName = "k8s-db-secret-flow",
                    expression =
                        "#[\"DB: \" ++ p('DB_HOST') ++ \":\" ++ p('DB_PORT') ++ \"/\" ++ p('DB_NAME') ++ \" (ssl=\" ++ p('DB_SSL_MODE') ++ \")\"]",
                    configs =
                        listOf(
                            global("DB_HOST", "postgres.db.svc.cluster.local"),
                            global("DB_PORT", "5432"),
                            global("DB_NAME", "myapp_production"),
                            global("DB_USER", "app_user"),
                            global("DB_PASSWORD", "SuperSecretPassword123!"),
                            global("DB_SSL_MODE", "require"),
                        ),
                )

            assertEquals(
                "DB: postgres.db.svc.cluster.local:5432/myapp_production (ssl=require)",
                result.event.payload?.value,
            )
            assertEquals("app_user", result.properties.getProperty("DB_USER"))
            assertEquals("SuperSecretPassword123!", result.properties.getProperty("DB_PASSWORD"))
        }

    @Test
    fun `should use service mesh configuration from environment variables`() =
        runTest {
            val istioConfig =
                """{"proxy":{"port":15001,"admin":15000},"tracing":{"enabled":true,"endpoint":"jaeger:9411"}}"""
            val result =
                evaluate(
                    flowName = "k8s-service-mesh-flow",
                    expression =
                        "#[\"Pod: \" ++ p('POD_NAME') ++ \" in namespace \" ++ p('POD_NAMESPACE') ++ \" (svc: \" ++ p('SERVICE_ACCOUNT') ++ \")\"]",
                    configs =
                        listOf(
                            global("ISTIO_METAJSON", istioConfig),
                            global("POD_NAME", "myapp-7d9f4b8c5-x2v9p"),
                            global("POD_NAMESPACE", "production"),
                            global("SERVICE_ACCOUNT", "myapp-sa"),
                            global("CLUSTER_DOMAIN", "cluster.local"),
                        ),
                )

            assertEquals(
                "Pod: myapp-7d9f4b8c5-x2v9p in namespace production (svc: myapp-sa)",
                result.event.payload?.value,
            )
            assertTrue(result.properties.getProperty("ISTIO_METAJSON").contains("jaeger:9411"))
            assertEquals("cluster.local", result.properties.getProperty("CLUSTER_DOMAIN"))
        }

    @Test
    fun `should use JVM system properties in expressions`() =
        runTest {
            val result =
                evaluate(
                    flowName = "jvm-props-flow",
                    expression = "#[\"Java \" ++ p('java.version') ++ \" by \" ++ p('java.vendor')]",
                    configs =
                        listOf(
                            global("java.version", "17.0.5"),
                            global("java.vendor", "Eclipse Adoptium"),
                            global("os.name", "Mac OS X"),
                            global("file.encoding", "UTF-8"),
                        ),
                )

            assertEquals("Java 17.0.5 by Eclipse Adoptium", result.event.payload?.value)
            assertEquals("Mac OS X", result.properties.getProperty("os.name"))
            assertEquals("UTF-8", result.properties.getProperty("file.encoding"))
        }

    @Test
    fun `should use JVM memory properties in expressions`() =
        runTest {
            val result =
                evaluate(
                    flowName = "jvm-memory-flow",
                    expression =
                        "#[\"Memory: \" ++ p('jvm.initial.memory') ++ \" / \" ++ p('jvm.max.memory') ++ \" using \" ++ p('jvm.gc.strategy')]",
                    configs =
                        listOf(
                            global("jvm.max.memory", "2048m"),
                            global("jvm.initial.memory", "512m"),
                            global("jvm.gc.strategy", "G1GC"),
                        ),
                )

            assertEquals("Memory: 512m / 2048m using G1GC", result.event.payload?.value)
        }

    @Test
    fun `should combine JVM properties with application properties`() =
        runTest {
            val fileConfig =
                configurationFile(
                    """
                    app.name=MyService
                    app.threads=10
                    """,
                )
            val result =
                evaluate(
                    flowName = "jvm-combined-flow",
                    expression =
                        "#[p('app.name') ++ \" running on \" ++ p('available.processors') ++ \" cores with \" ++ p('app.threads') ++ \" threads\"]",
                    configs =
                        listOf(
                            global("available.processors", "8"),
                            fileConfig,
                        ),
                )

            assertEquals("MyService running on 8 cores with 10 threads", result.event.payload?.value)
        }

    @Test
    fun `should use user home and working directory properties`() =
        runTest {
            val result =
                evaluate(
                    flowName = "jvm-dirs-flow",
                    expression = "#[\"Working: \" ++ p('user.dir') ++ \", Temp: \" ++ p('java.io.tmpdir')]",
                    configs =
                        listOf(
                            global("user.home", "/home/testuser"),
                            global("user.dir", "/home/testuser/projects/myapp"),
                            global("java.io.tmpdir", "/tmp"),
                        ),
                )

            assertEquals("Working: /home/testuser/projects/myapp, Temp: /tmp", result.event.payload?.value)
        }

    @Test
    fun `should load environment variables using EnvironmentProperties`() =
        runTest {
            val environment =
                mapOf(
                    "PATH" to "/usr/bin:/bin",
                    "HOME" to "/home/muse",
                )
            val result =
                evaluate(
                    flowName = "real-env-vars-flow",
                    expression = "#[\"PATH entries: \" ++ sizeOf(p('PATH') splitBy ':')]",
                    configs = listOf(EnvironmentProperties(docName = "System environment", docId = "system-environment")),
                    factory = defaultFactory(environment),
                )

            assertEquals("PATH entries: 2", result.event.payload?.value)
            assertTrue(result.properties.containsKey("PATH"))
            assertTrue(result.properties.containsKey("HOME"))
        }

    @Test
    fun `should load environment variables with prefix filter`() =
        runTest {
            val environment =
                mapOf(
                    "JAVA_HOME" to "/opt/jdk-21",
                    "JAVA_TOOL_OPTIONS" to "-XX:+UseContainerSupport",
                    "PATH" to "/usr/bin:/bin",
                )
            val result =
                evaluate(
                    flowName = "prefixed-env-flow",
                    expression = "#[\"Java installed at: \" ++ p('JAVA_HOME')]",
                    configs =
                        listOf(
                            EnvironmentProperties(
                                docName = "Java environment",
                                docId = "java-environment",
                                prefix = "JAVA_",
                            ),
                        ),
                    factory = defaultFactory(environment),
                )

            assertEquals("Java installed at: /opt/jdk-21", result.event.payload?.value)
            assertEquals("/opt/jdk-21", result.properties.getProperty("JAVA_HOME"))
            assertFalse(result.properties.containsKey("PATH"))
        }

    @Test
    fun `should load environment variables with prefix and remove prefix`() =
        runTest {
            val environment =
                mapOf(
                    "MAVEN_HOME" to "/opt/maven",
                    "PATH" to "/usr/bin:/bin",
                )
            val result =
                evaluate(
                    flowName = "maven-env-flow",
                    expression = "#[\"Maven home: \" ++ p('HOME')]",
                    configs =
                        listOf(
                            EnvironmentProperties(
                                docName = "Maven environment",
                                docId = "maven-environment",
                                prefix = "MAVEN_",
                                removePrefix = true,
                            ),
                        ),
                    factory = defaultFactory(environment),
                )

            assertEquals("Maven home: /opt/maven", result.event.payload?.value)
            assertEquals("/opt/maven", result.properties.getProperty("HOME"))
            assertFalse(result.properties.containsKey("PATH"))
        }

    @Test
    fun `should combine environment variables with application properties`() =
        runTest {
            val environment =
                mapOf(
                    "OS" to "Linux",
                    "PATH" to "/usr/bin:/bin",
                    "HOME" to "/home/muse",
                )
            val result =
                evaluate(
                    flowName = "combined-env-flow",
                    expression = "#[p('app.name') ++ \" v\" ++ p('app.version') ++ \" running on \" ++ p('OS')]",
                    configs =
                        listOf(
                            global("app.version", "1.2.3"),
                            global("app.name", "MuseApp"),
                            EnvironmentProperties(docName = "System environment", docId = "system-environment"),
                        ),
                    factory = defaultFactory(environment),
                )

            assertEquals("MuseApp v1.2.3 running on Linux", result.event.payload?.value)
            assertTrue(result.properties.containsKey("app.name"))
            assertTrue(result.properties.containsKey("app.version"))
            assertTrue(result.properties.containsKey("PATH"))
            assertTrue(result.properties.containsKey("HOME"))
        }

    @Test
    fun `should load Kubernetes style environment variables with prefix filters`() =
        runTest {
            val environment =
                mapOf(
                    "KUBERNETES_SERVICE_HOST" to "10.0.0.1",
                    "CONTAINER_NAME" to "muse-app",
                    "PATH" to "/usr/bin:/bin",
                )
            val result =
                evaluate(
                    flowName = "k8s-real-env-flow",
                    expression = "#[\"K8S host: \" ++ p('KUBERNETES_SERVICE_HOST')]",
                    configs =
                        listOf(
                            EnvironmentProperties(
                                docName = "Kubernetes environment",
                                docId = "kubernetes-environment",
                                prefix = "KUBERNETES_",
                            ),
                            EnvironmentProperties(
                                docName = "Container environment",
                                docId = "container-environment",
                                prefix = "CONTAINER_",
                            ),
                        ),
                    factory = defaultFactory(environment),
                )

            assertEquals("K8S host: 10.0.0.1", result.event.payload?.value)
            assertEquals("10.0.0.1", result.properties.getProperty("KUBERNETES_SERVICE_HOST"))
            assertEquals("muse-app", result.properties.getProperty("CONTAINER_NAME"))
            assertFalse(result.properties.containsKey("PATH"))
        }

    @Test
    fun `should load real JVM system properties`() =
        runTest {
            val result =
                evaluate(
                    flowName = "real-jvm-flow",
                    expression = "#[\"Java \" ++ p('java.version') ++ \" on \" ++ p('os.name')]",
                    configs = listOf(JvmSystemProperties(docName = "JVM properties", docId = "jvm-properties")),
                )

            val payload = result.event.payload?.value.toString()
            assertTrue(payload.contains("Java"))
            assertTrue(payload.contains("on"))
            assertTrue(result.properties.containsKey("java.version"))
            assertTrue(result.properties.containsKey("java.vendor"))
            assertTrue(result.properties.containsKey("os.name"))
            assertTrue(result.properties.containsKey("user.home"))
        }

    @Test
    fun `should load JVM properties with prefix filter`() =
        runTest {
            val result =
                evaluate(
                    flowName = "jvm-prefix-flow",
                    expression = "#[\"File encoding: \" ++ p('file.encoding')]",
                    configs =
                        listOf(
                            JvmSystemProperties(
                                docName = "File properties",
                                docId = "file-properties",
                                prefix = "file.",
                            ),
                        ),
                )

            assertTrue(result.event.payload?.value.toString().contains("File encoding:"))
            assertTrue(result.properties.containsKey("file.encoding"))
            assertTrue(result.properties.containsKey("file.separator"))
        }

    @Test
    fun `should combine real JVM properties with real environment variables`() =
        runTest {
            val environment =
                mapOf(
                    "PATH" to "/usr/bin:/bin",
                    "HOME" to "/home/muse",
                )
            val result =
                evaluate(
                    flowName = "combined-real-flow",
                    expression =
                        "#[\"Java: \" ++ p('java.version') ++ \", OS: \" ++ p('os.name') ++ \", User: \" ++ p('user.name')]",
                    configs =
                        listOf(
                            JvmSystemProperties(docName = "JVM properties", docId = "jvm-properties"),
                            EnvironmentProperties(docName = "System environment", docId = "system-environment"),
                        ),
                    factory = defaultFactory(environment),
                )

            val payload = result.event.payload?.value.toString()
            assertTrue(payload.contains("Java:"))
            assertTrue(payload.contains("OS:"))
            assertTrue(payload.contains("User:"))
            assertTrue(result.properties.containsKey("java.version"))
            assertTrue(result.properties.containsKey("os.name"))
            assertTrue(result.properties.containsKey("user.name"))
            assertTrue(result.properties.containsKey("PATH"))
            assertTrue(result.properties.containsKey("HOME"))
        }

    @Test
    fun `should demonstrate Spring Boot relaxed binding`() =
        runTest {
            val fileConfig =
                configurationFile(
                    """
                    db.host=localhost
                    db.port=5432
                    """,
                )
            val result =
                evaluate(
                    flowName = "spring-boot-binding-flow",
                    expression = "#[\"Host: \" ++ p('db.host') ++ \", Port: \" ++ p('db.port')]",
                    configs =
                        listOf(
                            fileConfig,
                            global("DB_HOST", "prod.db.server.com"),
                        ),
                )

            assertEquals("Host: prod.db.server.com, Port: 5432", result.event.payload?.value)
            assertEquals("prod.db.server.com", result.properties.getProperty("DB_HOST"))
        }

    @Test
    fun `should demonstrate all Spring Boot naming conventions`() =
        runTest {
            val result =
                evaluate(
                    flowName = "naming-conventions-flow",
                    expression = "#[p('app.config.server.host')]",
                    configs =
                        listOf(
                            global("appConfigServerHost", "camel-case-server"),
                            global("APP_CONFIG_SERVER_HOST", "snake-upper-server"),
                            global("app-config-server-host", "kebab-server"),
                        ),
                )

            assertEquals("kebab-server", result.event.payload?.value)
        }

    @Test
    fun `should override file properties with environment variables`() =
        runTest {
            val fileConfig =
                configurationFile(
                    """
                    db.host=localhost
                    db.port=5432
                    db.name=myapp_dev
                    """,
                )
            val result =
                evaluate(
                    flowName = "override-flow",
                    expression = "#[p('db.host') ++ \":\" ++ p('db.port')]",
                    configs =
                        listOf(
                            fileConfig,
                            global("db.host", "prod.db.server.com"),
                        ),
                )

            assertEquals("prod.db.server.com:5432", result.event.payload?.value)
            assertEquals("prod.db.server.com", result.properties.getProperty("db.host"))
        }

    @Test
    fun `should convert environment variable names to property format like Spring Boot`() =
        runTest {
            val result =
                evaluate(
                    flowName = "spring-boot-env-flow",
                    expression = "#[\"Host: \" ++ p('DB_HOST') ++ \", Port: \" ++ p('DATABASE_PORT')]",
                    configs =
                        listOf(
                            global("db.host", "localhost"),
                            global("db.port", "5432"),
                            global("DB_HOST", "spring.db.host.com"),
                            global("DATABASE_PORT", "3306"),
                        ),
                )

            assertEquals("Host: spring.db.host.com, Port: 3306", result.event.payload?.value)
            assertEquals("spring.db.host.com", result.properties.getProperty("DB_HOST"))
            assertEquals("3306", result.properties.getProperty("DATABASE_PORT"))
        }

    @Test
    fun `should demonstrate Spring Boot property priority order`() =
        runTest {
            val fileConfig =
                configurationFile(
                    """
                    app.name=FromPropertiesFile
                    app.version=1.0.0
                    """,
                )
            val result =
                evaluate(
                    flowName = "priority-flow",
                    expression = "#[p('app.name') ++ \" v\" ++ p('app.version')]",
                    configs =
                        listOf(
                            fileConfig,
                            global("app.version", "2.0.0"),
                        ),
                )

            assertEquals("FromPropertiesFile v2.0.0", result.event.payload?.value)
            assertEquals("FromPropertiesFile", result.properties.getProperty("app.name"))
            assertEquals("2.0.0", result.properties.getProperty("app.version"))
        }
}
