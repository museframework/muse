package dev.museframework.apikit.connector

import dev.museframework.apikit.ApiSpecificationEmptyException
import dev.museframework.apikit.ApiSpecificationNotConfiguredException
import dev.museframework.apikit.api.config.ApikitConfig
import dev.museframework.apikit.api.config.ApikitRef
import dev.museframework.apikit.api.config.ApikitRouter
import dev.museframework.apikit.api.error.ApikitErrorType
import dev.museframework.apikit.api.model.ApiMethod
import dev.museframework.apikit.api.model.ApiParameter
import dev.museframework.apikit.api.model.ApiResource
import dev.museframework.apikit.api.model.ApiSpecification
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.model.component.Processor
import dev.museframework.dsl.api.model.component.PipelineElement
import dev.museframework.dsl.api.model.component.Router
import dev.museframework.dsl.api.model.component.Source
import dev.museframework.dsl.api.model.config.MuseConfiguration
import dev.museframework.dsl.api.model.error.ErrorHandler
import dev.museframework.dsl.api.model.flow.FlowRef
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.policy.BackgroundAdmission
import dev.museframework.engine.api.execution.policy.BackgroundHandle
import dev.museframework.engine.api.execution.policy.BodyHandle
import dev.museframework.engine.api.execution.policy.PolicyDefinition
import dev.museframework.engine.api.execution.policy.PolicyGroup
import dev.museframework.engine.api.execution.policy.PolicyInvocation
import dev.museframework.engine.api.execution.policy.PolicyOutcome
import dev.museframework.engine.api.execution.policy.PolicyRouterDeclaration
import dev.museframework.engine.api.spi.ConfigBuilder
import dev.museframework.engine.api.spi.ProcessorBuilder
import dev.museframework.engine.api.spi.RouterBuilder
import dev.museframework.engine.api.spi.SourceBuilder
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.engine.api.spi.MuseComponentFactory
import dev.museframework.engine.api.spi.MuseContext
import dev.museframework.engine.api.streaming.MuseCursor
import dev.museframework.engine.api.streaming.MuseCursorProvider
import dev.museframework.http.api.model.HttpAttributes
import dev.museframework.http.api.model.HttpMethod
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.Properties
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ApikitConnectorRegistrarTest {
    @Test
    fun `specification remains mandatory when validations are disabled`() {
        val factory = RecordingFactory().also(ApikitConnectorRegistrar()::register)
        val context = TestContext(factory)

        assertFailsWith<ApiSpecificationNotConfiguredException> {
            factory.configBuilder.handle(
                ApikitConfig(name = "missing", spec = null, disableValidations = true),
                context,
            )
        }
    }

    @Test
    fun `specification without routes fails bootstrap even when validations are disabled`() {
        val factory = RecordingFactory().also(ApikitConnectorRegistrar()::register)
        val context = TestContext(factory)

        val failure =
            assertFailsWith<ApiSpecificationEmptyException> {
                factory.configBuilder.handle(
                    ApikitConfig(name = "empty", spec = "api/empty.raml", disableValidations = true),
                    context,
                )
            }

        assertEquals("api/empty.raml", failure.specPath)
    }

    @Test
    fun `disableValidations skips only header and query validation in the declared policy`() =
        runBlocking {
            val spec = specWithRequiredParameters()
            val flowName = "get:\\users:my-api"
            val factory = RecordingFactory().also(ApikitConnectorRegistrar()::register)
            val context =
                TestContext(factory, flows = mapOf(flowName to MuseFlow.SubFlow(name = flowName, docId = flowName)))
            factory.configBuilder.handle(
                ApikitConfig(name = "my-api", spec = "api/valid.raml", disableValidations = true),
                context,
            )
            context.properties["apikit.my-api.spec"] = spec
            assertEquals(false, context.properties["apikit.my-api.validationsEnabled"])

            val policy =
                factory.declaration.declare(
                    ApikitRouter(configRef = ApikitRef.ByName("my-api")),
                    StubDefinition(),
                    context,
                ).create(context)

            val outcome = policy.execute(StubInvocation(eventFor("/users")))

            assertIs<PolicyOutcome.Success>(outcome, "Disabled validations must not reject the request")
        }

    @Test
    fun `enabled validations reject a request missing a required header`() =
        runBlocking {
            val spec = specWithRequiredParameters()
            val flowName = "get:\\users:my-api"
            val factory = RecordingFactory().also(ApikitConnectorRegistrar()::register)
            val context =
                TestContext(factory, flows = mapOf(flowName to MuseFlow.SubFlow(name = flowName, docId = flowName)))
            context.properties["apikit.my-api.spec"] = spec
            context.properties["apikit.my-api.validationsEnabled"] = true

            val policy =
                factory.declaration.declare(
                    ApikitRouter(configRef = ApikitRef.ByName("my-api")),
                    StubDefinition(),
                    context,
                ).create(context)

            val outcome = policy.execute(StubInvocation(eventFor("/users")))

            val failure = assertIs<PolicyOutcome.Failure>(outcome)
            assertEquals(ApikitErrorType.MISSING_HEADER, failure.error.errorType)
        }

    private fun specWithRequiredParameters(): ApiSpecification =
        ApiSpecification(
            resources =
                listOf(
                    ApiResource(
                        path = "/users",
                        methods =
                            listOf(
                                ApiMethod(
                                    method = HttpMethod.GET,
                                    requiredHeaders = listOf(ApiParameter("X-Required", required = true)),
                                    requiredQueryParams = listOf(ApiParameter("required", required = true)),
                                ),
                            ),
                    ),
                ),
        )

    private fun eventFor(path: String): MuseEvent =
        MuseEvent(
            attributes =
                TypedValue.of(
                    HttpAttributes(
                        method = HttpMethod.GET,
                        requestPath = path,
                        queryParams = emptyMap(),
                        headers = emptyMap(),
                        requestUri = path,
                        remoteAddress = "127.0.0.1",
                    ),
                ),
        )

    private class StubDefinition : PolicyDefinition {
        override fun inline(elements: List<PipelineElement>): BodyHandle = TestHandle

        override fun flow(reference: FlowRef): BodyHandle = TestHandle

        override fun localHandler(handler: ErrorHandler) = Unit

        override fun background(maxConcurrency: Int?, timeoutMillis: Long?): BackgroundHandle =
            error("APIKit declares no background work")
    }

    private class StubInvocation(
        override val input: MuseEvent,
    ) : PolicyInvocation {
        override suspend fun call(
            body: BodyHandle,
            event: MuseEvent,
            timeoutMillis: Long?,
        ): PolicyOutcome = PolicyOutcome.Success(event)

        override suspend fun pause(millis: Long) = Unit

        override suspend fun <T> group(
            maxConcurrency: Int?,
            block: suspend PolicyGroup.() -> T,
        ): T = error("APIKit routing declares no group")

        override fun project(
            outcome: PolicyOutcome,
            target: TargetConfig,
            base: MuseEvent,
        ): PolicyOutcome = error("APIKit routing projects nothing")

        override fun <T : MuseCursorProvider<out MuseCursor>> manage(provider: T): T =
            error("APIKit routing manages nothing")

        override suspend fun submit(
            background: BackgroundHandle,
            body: BodyHandle,
            event: MuseEvent,
        ): BackgroundAdmission = error("APIKit routing declares no background work")
    }

    private object TestHandle : BodyHandle

    private class TestContext(
        override val factory: MuseComponentFactory,
        override val flows: Map<String, MuseFlow> = emptyMap(),
    ) : MuseContext {
        override val properties: Properties = Properties()
        override val expressionEvaluator: MuseExpressionEvaluator =
            object : MuseExpressionEvaluator {
                override fun evaluate(
                    expression: String,
                    event: MuseEvent,
                ): TypedValue<Any>? = null
            }
        override val configs: Map<String, MuseConfiguration> = emptyMap()
    }

    private class RecordingFactory : MuseComponentFactory {
        override fun <T : Router> registerPolicyRouter(
            type: KClass<T>,
            declaration: PolicyRouterDeclaration<T>,
        ) {
            if (type == ApikitRouter::class) {
                @Suppress("UNCHECKED_CAST")
                this.declaration = declaration as PolicyRouterDeclaration<ApikitRouter>
            }
        }

        lateinit var configBuilder: ConfigBuilder<ApikitConfig>
        lateinit var declaration: PolicyRouterDeclaration<ApikitRouter>

        @Suppress("UNCHECKED_CAST")
        override fun <T : MuseConfiguration> registerConfig(
            type: KClass<T>,
            builder: ConfigBuilder<T>,
        ) {
            if (type == ApikitConfig::class) configBuilder = builder as ConfigBuilder<ApikitConfig>
        }

        override fun <T : Processor> registerProcessor(
            type: KClass<T>,
            builder: ProcessorBuilder<T>,
        ) = Unit

        override fun <T : Router> registerRouter(
            type: KClass<T>,
            builder: RouterBuilder<T>,
        ) = Unit

        override fun <T : Source> registerSource(
            type: KClass<T>,
            builder: SourceBuilder<T>,
        ) = Unit

        override fun createProcessor(
            component: Processor,
            context: MuseContext,
        ): Any = error("The empty test flow has no processors")

        override fun createRouter(
            component: Router,
            context: MuseContext,
        ): Any = error("The empty test flow has no nested routers")
    }
}
