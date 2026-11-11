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
import kotlin.test.assertNull
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
    fun `rejects unsupported media type without invoking the prepared flow`() =
        routing(
            spec(resource("/users", HttpMethod.POST, contentTypes = listOf("application/octet-stream"))),
            routedFlows = setOf("post:\\users:myApi"),
        ) { routing ->
            val invocation = RecordingInvocation(
                event("/users", HttpMethod.POST, headers = mapOf("Content-Type" to "application/json")),
            )

            assertFailure(routing.execute(invocation), ApikitErrorType.UNSUPPORTED_MEDIA_TYPE)
            assertNull(invocation.calledHandle)
            assertNull(invocation.calledEvent)
        }

    @Test
    fun `accepts matching media type with mixed header case and a charset`() =
        routing(
            spec(resource("/users", HttpMethod.POST, contentTypes = listOf("application/octet-stream"))),
            routedFlows = setOf("post:\\users:myApi"),
        ) { routing ->
            val invocation = RecordingInvocation(
                event("/users", HttpMethod.POST, headers = mapOf("content-type" to "Application/Octet-Stream; charset=UTF-8")),
            )

            assertIs<PolicyOutcome.Success>(routing.execute(invocation))
            assertNotNull(invocation.calledHandle)
        }

    @Test
    fun `missing Content-Type on a constrained operation fails before the flow is called`() =
        routing(
            spec(resource("/users", HttpMethod.POST, contentTypes = listOf("application/octet-stream"))),
            routedFlows = setOf("post:\\users:myApi"),
        ) { routing ->
            val invocation = RecordingInvocation(event("/users", HttpMethod.POST))

            assertFailure(routing.execute(invocation), ApikitErrorType.UNSUPPORTED_MEDIA_TYPE)
            assertNull(invocation.calledHandle)
        }

    @Test
    fun `disableValidations bypasses media type checks but still resolves routes`() =
        routing(
            spec(resource("/users", HttpMethod.POST, contentTypes = listOf("application/octet-stream"))),
            routedFlows = setOf("post:\\users:myApi"),
            validationsEnabled = false,
        ) { routing ->
            val invocation = RecordingInvocation(
                event("/users", HttpMethod.POST, headers = mapOf("Content-Type" to "application/json")),
            )

            assertIs<PolicyOutcome.Success>(routing.execute(invocation))
            assertNotNull(invocation.calledHandle)
        }

    @Test
    fun `method and path mismatches take precedence over Content-Type validation`() =
        routing(spec(resource("/users", HttpMethod.POST, contentTypes = listOf("application/octet-stream")))) { routing ->
            assertFailure(
                routing.execute(RecordingInvocation(event("/missing", HttpMethod.POST, headers = mapOf("Content-Type" to "application/json")))),
                ApikitErrorType.NOT_FOUND,
            )
            assertFailure(
                routing.execute(RecordingInvocation(event("/users", HttpMethod.PUT, headers = mapOf("Content-Type" to "application/json")))),
                ApikitErrorType.METHOD_NOT_ALLOWED,
            )
        }

    @Test
    fun `fails with BAD_REQUEST when a required header is absent`() =
        routing(
            spec(resource("/users", HttpMethod.GET, requiredHeaders = listOf(ApiParameter("X-Required", required = true)))),
        ) { routing ->
            assertFailure(
                routing.execute(RecordingInvocation(event("/users", HttpMethod.GET))),
                ApikitErrorType.BAD_REQUEST,
            )
        }

    @Test
    fun `fails with BAD_REQUEST when a required query parameter is absent`() =
        routing(
            spec(resource("/users", HttpMethod.GET, requiredQueryParams = listOf(ApiParameter("required", required = true)))),
        ) { routing ->
            assertFailure(
                routing.execute(RecordingInvocation(event("/users", HttpMethod.GET))),
                ApikitErrorType.BAD_REQUEST,
            )
        }

    @Test
    fun `unsupported Content-Type takes precedence over missing implementation flow`() =
        routing(
            spec(resource("/users", HttpMethod.POST, contentTypes = listOf("application/octet-stream"))),
            routedFlows = emptySet(),
        ) { routing ->
            val invocation = RecordingInvocation(
                event("/users", HttpMethod.POST, headers = mapOf("Content-Type" to "application/json")),
            )

            assertFailure(routing.execute(invocation), ApikitErrorType.UNSUPPORTED_MEDIA_TYPE)
            assertNull(invocation.calledHandle)
        }

    @Test
    fun `fails with NOT_IMPLEMENTED when the matched route has no declared flow`() =
        routing(spec(resource("/users", HttpMethod.GET)), routedFlows = emptySet()) { routing ->
            assertFailure(
                routing.execute(RecordingInvocation(event("/users", HttpMethod.GET))),
                ApikitErrorType.NOT_IMPLEMENTED,
            )
        }

    @Test
    fun `required header validation precedes Content-Type and missing flow checks`() =
        routing(
            spec(
                resource(
                    "/users",
                    HttpMethod.POST,
                    requiredHeaders = listOf(ApiParameter("X-Required", required = true)),
                    contentTypes = listOf("application/octet-stream"),
                ),
            ),
            routedFlows = emptySet(),
        ) { routing ->
            val invocation = RecordingInvocation(
                event("/users", HttpMethod.POST, headers = mapOf("Content-Type" to "application/json")),
            )

            assertFailure(routing.execute(invocation), ApikitErrorType.BAD_REQUEST)
            assertNull(invocation.calledHandle)
        }

    @Test
    fun `required query validation precedes Content-Type and missing flow checks`() =
        routing(
            spec(
                resource(
                    "/users",
                    HttpMethod.POST,
                    requiredQueryParams = listOf(ApiParameter("required", required = true)),
                    contentTypes = listOf("application/octet-stream"),
                ),
            ),
            routedFlows = emptySet(),
        ) { routing ->
            val invocation = RecordingInvocation(
                event("/users", HttpMethod.POST, headers = mapOf("Content-Type" to "application/json")),
            )

            assertFailure(routing.execute(invocation), ApikitErrorType.BAD_REQUEST)
            assertNull(invocation.calledHandle)
        }

    @Test
    fun `valid media type without implementation yields NOT_IMPLEMENTED`() =
        routing(
            spec(resource("/users", HttpMethod.POST, contentTypes = listOf("application/octet-stream"))),
            routedFlows = emptySet(),
        ) { routing ->
            val invocation = RecordingInvocation(
                event("/users", HttpMethod.POST, headers = mapOf("Content-Type" to "application/octet-stream")),
            )

            assertFailure(routing.execute(invocation), ApikitErrorType.NOT_IMPLEMENTED)
            assertNull(invocation.calledHandle)
        }

    @Test
    fun `disableValidations skips optional checks but still reports NOT_IMPLEMENTED`() =
        routing(
            spec(resource("/users", HttpMethod.POST, contentTypes = listOf("application/octet-stream"))),
            routedFlows = emptySet(),
            validationsEnabled = false,
        ) { routing ->
            val invocation = RecordingInvocation(
                event("/users", HttpMethod.POST, headers = mapOf("Content-Type" to "application/json")),
            )

            assertFailure(routing.execute(invocation), ApikitErrorType.NOT_IMPLEMENTED)
            assertNull(invocation.calledHandle)
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

    @Test
    fun `valid but incompatible Accept produces NOT_ACCEPTABLE without invoking flow`() =
        routing(spec(resource("/users", HttpMethod.GET, responseMediaTypes = listOf("application/json")))) { routing ->
            val invocation = RecordingInvocation(event("/users", HttpMethod.GET, headers = mapOf("Accept" to "application/xml")))
            assertFailure(routing.execute(invocation), ApikitErrorType.NOT_ACCEPTABLE)
            assertNull(invocation.calledHandle)
            assertNull(invocation.calledEvent)
        }

    @Test
    fun `Accept checks respect route priority and bypass without masking missing flows`() {
        routing(spec(resource("/users", HttpMethod.GET, responseMediaTypes = listOf("application/json"))), routedFlows = emptySet()) { routing ->
            assertFailure(routing.execute(RecordingInvocation(event("/missing", HttpMethod.GET, mapOf("Accept" to "application/xml")))), ApikitErrorType.NOT_FOUND)
            assertFailure(routing.execute(RecordingInvocation(event("/users", HttpMethod.POST, mapOf("Accept" to "application/xml")))), ApikitErrorType.METHOD_NOT_ALLOWED)
            assertFailure(routing.execute(RecordingInvocation(event("/users", HttpMethod.GET, mapOf("Accept" to "application/xml")))), ApikitErrorType.NOT_ACCEPTABLE)
            assertFailure(routing.execute(RecordingInvocation(event("/users", HttpMethod.GET, mapOf("Accept" to "application/json")))), ApikitErrorType.NOT_IMPLEMENTED)
        }
        routing(
            spec(resource("/users", HttpMethod.GET, responseMediaTypes = listOf("application/json"))),
            routedFlows = emptySet(), validationsEnabled = false,
        ) { routing ->
            assertFailure(routing.execute(RecordingInvocation(event("/users", HttpMethod.GET, mapOf("Accept" to "application/xml")))), ApikitErrorType.NOT_IMPLEMENTED)
        }
    }

    @Test
    fun `malformed Accept has a deterministic BAD_REQUEST outcome`() =
        routing(spec(resource("/users", HttpMethod.GET, responseMediaTypes = listOf("application/json")))) { routing ->
            val invocation = RecordingInvocation(event("/users", HttpMethod.GET, headers = mapOf("Accept" to "application/json;q=2")))
            assertFailure(routing.execute(invocation), ApikitErrorType.BAD_REQUEST)
            assertNull(invocation.calledHandle)
        }

    @Test
    fun `valid Accept rejection precedes invalid request Content-Type`() =
        routing(spec(resource("/users", HttpMethod.POST, contentTypes = listOf("application/octet-stream"), responseMediaTypes = listOf("application/json"))),
            routedFlows = setOf("post:\\users:myApi"),
        ) { routing ->
            val invocation = RecordingInvocation(event("/users", HttpMethod.POST, mapOf("Accept" to "application/xml", "Content-Type" to "text/plain")))
            assertFailure(routing.execute(invocation), ApikitErrorType.NOT_ACCEPTABLE)
            assertNull(invocation.calledHandle)
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
        validationsEnabled: Boolean = true,
        block: suspend (PreparedRouting) -> Unit,
    ) = runBlocking { PreparedRouting(spec, routedFlows, validationsEnabled).use { block(it) } }

    private fun spec(vararg resources: ApiResource): ApiSpecification = ApiSpecification(resources = resources.toList())

    private fun resource(
        path: String,
        method: HttpMethod,
        requiredHeaders: List<ApiParameter> = emptyList(),
        requiredQueryParams: List<ApiParameter> = emptyList(),
        contentTypes: List<String> = emptyList(),
        responseMediaTypes: List<String> = emptyList(),
    ): ApiResource =
        ApiResource(
            path = path,
            methods =
                listOf(
                    ApiMethod(
                        method = method,
                        requiredHeaders = requiredHeaders,
                        requiredQueryParams = requiredQueryParams,
                        contentTypes = contentTypes,
                        responseMediaTypes = responseMediaTypes,
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
    validationsEnabled: Boolean,
) : AutoCloseable {
    private val handles = routedFlows.associateWith { TestBodyHandle() }
    private val policy =
        ApikitRoutingPolicy(
            component = ApikitRouter(configRef = ApikitRef.ByName(CONFIG_NAME)),
            routingTable = RoutingTable(spec, CONFIG_NAME),
            apiSpecification = spec,
            flowHandles = handles,
            validationsEnabled = validationsEnabled,
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
