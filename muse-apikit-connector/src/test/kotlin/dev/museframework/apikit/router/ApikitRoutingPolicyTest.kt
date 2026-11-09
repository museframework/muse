package dev.museframework.apikit.router

import dev.museframework.apikit.api.config.ApikitRef
import dev.museframework.apikit.api.config.ApikitRouter
import dev.museframework.apikit.api.error.ApikitErrorType
import dev.museframework.apikit.api.model.ApiMethod
import dev.museframework.apikit.api.model.ApiParameter
import dev.museframework.apikit.api.model.ApiResource
import dev.museframework.apikit.api.model.ApiSpecification
import dev.museframework.apikit.routing.RoutingTable
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.policy.BackgroundAdmission
import dev.museframework.engine.api.execution.policy.BackgroundHandle
import dev.museframework.engine.api.execution.policy.BodyHandle
import dev.museframework.engine.api.execution.policy.PolicyGroup
import dev.museframework.engine.api.execution.policy.PolicyInvocation
import dev.museframework.engine.api.execution.policy.PolicyOutcome
import dev.museframework.engine.api.streaming.MuseCursor
import dev.museframework.engine.api.streaming.MuseCursorProvider
import dev.museframework.http.api.model.HttpAttributes
import dev.museframework.http.api.model.HttpMethod
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * Routing decisions of the declared APIKit policy: it selects one prepared handle, validates the
 * matched method and never reconstructs a pipeline or a callee's error handler.
 */
class ApikitRoutingPolicyTest {
    @Test
    fun `matches a route and calls the prepared handle with the caller event`() =
        routing(spec(resource("/users", HttpMethod.GET))) { routing ->
            val invocation = RecordingInvocation(event("/users", HttpMethod.GET))

            val outcome = routing.execute(invocation)

            assertIs<PolicyOutcome.Success>(outcome)
            assertSame(routing.handleFor("get:\\users:myApi"), invocation.calledHandle)
            val called = assertNotNull(invocation.calledEvent, "The caller's event reaches the prepared handle")
            assertEquals(invocation.input.payload, called.payload)
            assertEquals(invocation.input.attributes, called.attributes)
            assertEquals(invocation.input.correlationId, called.correlationId)
            assertEquals(emptyMap<String, String>(), called.variables["pathParams"]?.value)
        }

    @Test
    fun `declares path parameters on the event it hands to the prepared handle`() =
        routing(spec(resource("/users/{id}", HttpMethod.GET))) { routing ->
            val invocation = RecordingInvocation(event("/users/42", HttpMethod.GET))

            routing.execute(invocation)

            assertEquals(mapOf("id" to "42"), invocation.calledEvent?.variables?.get("pathParams")?.value)
            assertSame(invocation.input.payload, invocation.calledEvent?.payload)
            assertEquals("caller-correlation", invocation.calledEvent?.correlationId)
        }

    @Test
    fun `hands the prepared handle's own outcome back unchanged`() =
        routing(spec(resource("/users", HttpMethod.GET))) { routing ->
            val invocation = RecordingInvocation(event("/users", HttpMethod.GET))
            val handled = PolicyOutcome.Failure(unhandled("handled by the callee owner"), invocation.input)
            invocation.answer { handled }

            val outcome = routing.execute(invocation)

            assertSame(handled, outcome, "The router never substitutes or re-applies a callee's handler")
        }

    @Test
    fun `passes when required headers and query parameters are present`() =
        routing(
            spec(
                resource(
                    "/users",
                    HttpMethod.GET,
                    requiredHeaders = listOf(ApiParameter("X-Required", required = true)),
                    requiredQueryParams = listOf(ApiParameter("required", required = true)),
                ),
            ),
        ) { routing ->
            val invocation =
                RecordingInvocation(
                    event(
                        "/users",
                        HttpMethod.GET,
                        headers = mapOf("X-Required" to "yes"),
                        queryParams = mapOf("required" to "yes"),
                    ),
                )

            val outcome = routing.execute(invocation)

            assertIs<PolicyOutcome.Success>(outcome)
            assertSame(routing.handleFor("get:\\users:myApi"), invocation.calledHandle)
        }

    @Test
    fun `fails with NOT_FOUND when no route matches the path`() =
        routing(spec(resource("/users", HttpMethod.GET))) { routing ->
            assertFailure(
                routing.execute(RecordingInvocation(event("/orders", HttpMethod.GET))),
                ApikitErrorType.NOT_FOUND,
            )
        }

    @Test
    fun `fails with METHOD_NOT_ALLOWED when the path exists without the method`() =
        routing(spec(resource("/users", HttpMethod.GET))) { routing ->
            assertFailure(
                routing.execute(RecordingInvocation(event("/users", HttpMethod.POST))),
                ApikitErrorType.METHOD_NOT_ALLOWED,
            )
        }

    @Test
    fun `fails with MISSING_HEADER when a required header is absent`() =
        routing(
            spec(resource("/users", HttpMethod.GET, requiredHeaders = listOf(ApiParameter("X-Required", required = true)))),
        ) { routing ->
            assertFailure(
                routing.execute(RecordingInvocation(event("/users", HttpMethod.GET))),
                ApikitErrorType.MISSING_HEADER,
            )
        }

    @Test
    fun `fails with MISSING_QUERY_PARAM when a required query parameter is absent`() =
        routing(
            spec(resource("/users", HttpMethod.GET, requiredQueryParams = listOf(ApiParameter("required", required = true)))),
        ) { routing ->
            assertFailure(
                routing.execute(RecordingInvocation(event("/users", HttpMethod.GET))),
                ApikitErrorType.MISSING_QUERY_PARAM,
            )
        }

    @Test
    fun `fails with FLOW_NOT_FOUND when the matched route has no declared flow`() =
        routing(spec(resource("/users", HttpMethod.GET)), routedFlows = emptySet()) { routing ->
            assertFailure(
                routing.execute(RecordingInvocation(event("/users", HttpMethod.GET))),
                ApikitErrorType.FLOW_NOT_FOUND,
            )
        }

    @Test
    fun `fails with BAD_REQUEST when the event carries no HTTP attributes`() =
        routing(spec(resource("/users", HttpMethod.GET))) { routing ->
            assertFailure(
                routing.execute(RecordingInvocation(MuseEvent(payload = TypedValue.of("not-http")))),
                ApikitErrorType.BAD_REQUEST,
            )
        }

    @Test
    fun `matches routes that are declared under a base path`() =
        routing(ApiSpecification(basePath = "/api", resources = listOf(resource("/users", HttpMethod.GET)))) { routing ->
            val invocation = RecordingInvocation(event("/api/users", HttpMethod.GET))

            val outcome = routing.execute(invocation)

            assertIs<PolicyOutcome.Success>(outcome)
            assertSame(routing.handleFor("get:\\api\\users:myApi"), invocation.calledHandle)
        }

    private fun assertFailure(
        outcome: PolicyOutcome,
        expected: ErrorType,
    ) {
        val failure = assertIs<PolicyOutcome.Failure>(outcome)
        assertEquals(expected, failure.error.errorType)
        assertSame(failure.error, failure.event.error)
    }

    private fun routing(
        spec: ApiSpecification,
        routedFlows: Set<String> =
            setOf("get:\\users:myApi", "get:\\users\\{id}:myApi", "get:\\api\\users:myApi"),
        block: suspend (PreparedRouting) -> Unit,
    ) = runBlocking { PreparedRouting(spec, routedFlows).use { block(it) } }

    private fun spec(vararg resources: ApiResource): ApiSpecification = ApiSpecification(resources = resources.toList())

    private fun resource(
        path: String,
        method: HttpMethod,
        requiredHeaders: List<ApiParameter> = emptyList(),
        requiredQueryParams: List<ApiParameter> = emptyList(),
    ): ApiResource =
        ApiResource(
            path = path,
            methods =
                listOf(
                    ApiMethod(
                        method = method,
                        requiredHeaders = requiredHeaders,
                        requiredQueryParams = requiredQueryParams,
                    ),
                ),
        )

    private fun unhandled(description: String): MuseError =
        MuseError(
            description = description,
            detailedDescription = description,
            errorType = ErrorType.Mule.UNKNOWN,
            cause = IllegalStateException(description),
        )
}

private class PreparedRouting(
    spec: ApiSpecification,
    routedFlows: Set<String>,
) : AutoCloseable {
    private val handles = routedFlows.associateWith { TestBodyHandle() }
    private val policy =
        ApikitRoutingPolicy(
            component = ApikitRouter(configRef = ApikitRef.ByName(CONFIG_NAME)),
            routingTable = RoutingTable(spec, CONFIG_NAME),
            apiSpecification = spec,
            flowHandles = handles,
            validationsEnabled = true,
        )

    suspend fun execute(invocation: PolicyInvocation): PolicyOutcome = policy.execute(invocation)

    fun handleFor(flowName: String): BodyHandle = checkNotNull(handles[flowName]) { "No declared handle for $flowName" }

    override fun close() = Unit

    private companion object {
        const val CONFIG_NAME = "myApi"
    }
}

private class TestBodyHandle : BodyHandle

private fun event(
    path: String,
    method: HttpMethod,
    headers: Map<String, String> = emptyMap(),
    queryParams: Map<String, String> = emptyMap(),
): MuseEvent =
    MuseEvent(
        payload = TypedValue.of("caller-payload"),
        attributes =
            TypedValue.of(
                HttpAttributes(
                    method = method,
                    requestPath = path,
                    queryParams = queryParams,
                    headers = headers,
                    requestUri = path,
                    remoteAddress = "127.0.0.1",
                ),
            ),
        correlationId = "caller-correlation",
    )

private class RecordingInvocation(
    override val input: MuseEvent,
) : PolicyInvocation {
    var calledHandle: BodyHandle? = null
        private set
    var calledEvent: MuseEvent? = null
        private set
    private var answer: (MuseEvent) -> PolicyOutcome = { PolicyOutcome.Success(it) }

    fun answer(outcome: (MuseEvent) -> PolicyOutcome) {
        answer = outcome
    }

    override suspend fun call(
        body: BodyHandle,
        event: MuseEvent,
        timeoutMillis: Long?,
    ): PolicyOutcome {
        calledHandle = body
        calledEvent = event
        return answer(event)
    }

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

    override fun <T : MuseCursorProvider<out MuseCursor>> manage(provider: T): T = error("APIKit routing manages nothing")

    override suspend fun submit(
        background: BackgroundHandle,
        body: BodyHandle,
        event: MuseEvent,
    ): BackgroundAdmission = error("APIKit routing declares no background work")
}
