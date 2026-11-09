package dev.museframework.apikit.router

import dev.museframework.apikit.api.config.ApikitRef
import dev.museframework.apikit.api.config.ApikitRouter
import dev.museframework.apikit.api.error.ApikitErrorType
import dev.museframework.apikit.api.model.ApiMethod
import dev.museframework.apikit.api.model.ApiSpecification
import dev.museframework.apikit.api.routing.RoutingResult
import dev.museframework.apikit.routing.RequestValidator
import dev.museframework.apikit.routing.RoutingTable
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.policy.BodyHandle
import dev.museframework.engine.api.execution.policy.ExecutionPolicy
import dev.museframework.engine.api.execution.policy.PolicyFactory
import dev.museframework.engine.api.execution.policy.PolicyInvocation
import dev.museframework.engine.api.execution.policy.PolicyOutcome
import dev.museframework.engine.api.execution.policy.PolicyRouterDeclaration
import dev.museframework.engine.api.spi.MuseContext
import dev.museframework.dsl.api.model.flow.FlowRef
import dev.museframework.http.api.model.HttpAttributes

/**
 * Event-time APIKit routing.
 *
 * The router only selects among handles that were declared at bootstrap, validates the matched
 * method and hands the caller's event to that prepared occurrence. It never reconstructs a flow
 * pipeline or a callee's error handler: the referenced flow's own owner decides.
 */
class ApikitRoutingPolicy(
    private val component: ApikitRouter,
    private val routingTable: RoutingTable,
    private val apiSpecification: ApiSpecification,
    private val flowHandles: Map<String, BodyHandle>,
    private val validationsEnabled: Boolean = true,
) : ExecutionPolicy {
    override suspend fun execute(invocation: PolicyInvocation): PolicyOutcome {
        val httpAttributes =
            invocation.input.httpAttributes()
                ?: return failure(
                    errorType = ApikitErrorType.BAD_REQUEST,
                    description = "Missing HTTP attributes",
                    detailedDescription =
                        "The event does not contain HTTP attributes. Ensure the request is received through an HTTP listener.",
                    cause = IllegalStateException("Missing HTTP attributes"),
                    event = invocation.input,
                )

        return when (val routingResult = routingTable.resolve(httpAttributes.requestPath, httpAttributes.method)) {
            is RoutingResult.Match -> handleMatch(invocation, routingResult, httpAttributes)
            is RoutingResult.NotFound ->
                failure(
                    errorType = ApikitErrorType.NOT_FOUND,
                    description = "Resource not found: ${routingResult.path}",
                    detailedDescription = "No API resource matches the requested path '${routingResult.path}'",
                    cause = NoSuchElementException("Path not found: ${routingResult.path}"),
                    event = invocation.input,
                )

            is RoutingResult.MethodNotAllowed ->
                failure(
                    errorType = ApikitErrorType.METHOD_NOT_ALLOWED,
                    description = "Method not allowed: ${routingResult.path}",
                    detailedDescription =
                        "The HTTP method is not allowed for this resource. Allowed methods: " +
                            routingResult.allowedMethods.joinToString(", "),
                    cause = UnsupportedOperationException("Method not allowed"),
                    event = invocation.input,
                )

            is RoutingResult.ValidationFailed -> handleValidationFailed(invocation, routingResult)
        }
    }

    private suspend fun handleMatch(
        invocation: PolicyInvocation,
        result: RoutingResult.Match,
        httpAttributes: HttpAttributes,
    ): PolicyOutcome {
        val flowName = result.flowName

        val apiMethod =
            findApiMethod(flowName, httpAttributes.method)
                ?: return flowNotFound(invocation, flowName)

        val handle =
            flowHandles[flowName]
                ?: return flowNotFound(invocation, flowName)

        if (validationsEnabled) {
            val missingHeaders = RequestValidator.validateHeaders(apiMethod, httpAttributes.headers)
            if (missingHeaders.isNotEmpty()) {
                return failure(
                    errorType = ApikitErrorType.MISSING_HEADER,
                    description = "Missing required header(s): ${missingHeaders.joinToString(", ")}",
                    detailedDescription =
                        "The request is missing required header(s): ${missingHeaders.joinToString(", ")}. " +
                            "Include these headers in your request.",
                    cause = IllegalArgumentException("Missing headers: ${missingHeaders.joinToString(", ")}"),
                    event = invocation.input,
                )
            }

            val missingQueryParams = RequestValidator.validateQueryParams(apiMethod, httpAttributes.queryParams)
            if (missingQueryParams.isNotEmpty()) {
                return failure(
                    errorType = ApikitErrorType.MISSING_QUERY_PARAM,
                    description = "Missing required query parameter(s): ${missingQueryParams.joinToString(", ")}",
                    detailedDescription =
                        "The request is missing required query parameter(s): " +
                            "${missingQueryParams.joinToString(", ")}. Include these parameters in your request.",
                    cause = IllegalArgumentException("Missing query params: ${missingQueryParams.joinToString(", ")}"),
                    event = invocation.input,
                )
            }
        }

        return invocation.call(handle, invocation.input.withPathParams(result.pathParams))
    }

    private fun handleValidationFailed(
        invocation: PolicyInvocation,
        result: RoutingResult.ValidationFailed,
    ): PolicyOutcome =
        if (result.missingHeaders.isNotEmpty()) {
            failure(
                errorType = ApikitErrorType.MISSING_HEADER,
                description = "Missing required header(s): ${result.missingHeaders.joinToString(", ")}",
                detailedDescription =
                    "The request is missing required header(s): ${result.missingHeaders.joinToString(", ")}. " +
                        "Include these headers in your request.",
                cause = IllegalArgumentException("Missing headers: ${result.missingHeaders.joinToString(", ")}"),
                event = invocation.input,
            )
        } else {
            failure(
                errorType = ApikitErrorType.MISSING_QUERY_PARAM,
                description = "Missing required query parameter(s): ${result.missingQueryParams.joinToString(", ")}",
                detailedDescription =
                    "The request is missing required query parameter(s): " +
                        "${result.missingQueryParams.joinToString(", ")}. Include these parameters in your request.",
                cause = IllegalArgumentException("Missing query params: ${result.missingQueryParams.joinToString(", ")}"),
                event = invocation.input,
            )
        }

    private fun flowNotFound(invocation: PolicyInvocation, flowName: String): PolicyOutcome =
        failure(
            errorType = ApikitErrorType.FLOW_NOT_FOUND,
            description = "Flow not found: $flowName",
            detailedDescription =
                "The API specification defines this route, but no implementation flow was found with name " +
                    "'$flowName'. Create a flow with this name to handle the request.",
            cause = NoSuchElementException("Flow not found: $flowName"),
            event = invocation.input,
        )

    private fun failure(
        errorType: dev.museframework.dsl.api.model.error.ErrorType,
        description: String,
        detailedDescription: String,
        cause: Throwable,
        event: MuseEvent,
    ): PolicyOutcome.Failure {
        val error =
            MuseError(
                description = description,
                detailedDescription = detailedDescription,
                errorType = errorType,
                cause = cause,
            )
        return PolicyOutcome.Failure(error, event.copy(error = error))
    }

    private fun MuseEvent.httpAttributes(): HttpAttributes? = attributes?.value as? HttpAttributes

    private fun MuseEvent.withPathParams(pathParams: Map<String, String>): MuseEvent {
        val updated: Map<String, TypedValue<Any>?> = variables + ("pathParams" to TypedValue.of(pathParams))
        return copy(variables = updated)
    }

    private fun findApiMethod(
        flowName: String,
        method: dev.museframework.http.api.model.HttpMethod,
    ): ApiMethod? {
        val basePath = if (apiSpecification.basePath == "/") "" else apiSpecification.basePath
        val configName = configName(component)
        for (resource in apiSpecification.resources) {
            val fullPath = "$basePath${resource.path}"
            for (apiMethod in resource.methods) {
                if (apiMethod.method == method) {
                    val expectedFlowName = "${method.name.lowercase()}:${fullPath.replace("/", "\\")}:$configName"
                    if (expectedFlowName == flowName) return apiMethod
                }
            }
        }
        return null
    }

    internal companion object {
        internal fun configName(component: ApikitRouter): String =
            when (val ref = component.configRef) {
                is ApikitRef.ByName -> ref.name
                is ApikitRef.ByReference -> ref.config.name
            }
    }
}

/**
 * Declares one prepared handle per routed flow after the specification-derived routing table
 * exists. A route whose flow is absent from the application keeps its missing-route semantics at
 * event time instead of becoming a bootstrap failure.
 */
internal val apikitPolicyDeclaration =
    PolicyRouterDeclaration<ApikitRouter> { component, definition, context ->
        val configName = ApikitRoutingPolicy.configName(component)
        val spec =
            context.properties["apikit.$configName.spec"] as? ApiSpecification
                ?: throw IllegalStateException("ApiSpecification not found for config ref: $configName")
        val validationsEnabled =
            context.properties["apikit.$configName.validationsEnabled"] as? Boolean
                ?: true
        val routingTable = RoutingTable(spec, configName)
        // Every routed flow must be declared while this occurrence is still open: the factory runs
        // at init, when a body can no longer be declared.
        val flowHandles = declaredFlowHandles(spec, configName, definition, context)

        PolicyFactory {
            ApikitRoutingPolicy(
                component = component,
                routingTable = routingTable,
                apiSpecification = spec,
                flowHandles = flowHandles,
                validationsEnabled = validationsEnabled,
            )
        }
    }

private fun declaredFlowHandles(
    spec: ApiSpecification,
    configName: String,
    definition: dev.museframework.engine.api.execution.policy.PolicyDefinition,
    context: MuseContext,
): Map<String, BodyHandle> =
    buildMap {
        val basePath = if (spec.basePath == "/") "" else spec.basePath
        for (resource in spec.resources) {
            val fullPath = "$basePath${resource.path}"
            for (apiMethod in resource.methods) {
                val flowName = "${apiMethod.method.name.lowercase()}:${fullPath.replace("/", "\\")}:$configName"
                if (context.flows.containsKey(flowName)) {
                    put(flowName, definition.flow(FlowRef.ByName(flowName)))
                }
            }
        }
    }
