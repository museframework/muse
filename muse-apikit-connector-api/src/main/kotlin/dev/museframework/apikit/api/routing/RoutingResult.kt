package dev.museframework.apikit.api.routing

import dev.museframework.http.api.model.HttpMethod

sealed class RoutingResult {
    data class Match(
        val flowName: String,
        val pathParams: Map<String, String>,
    ) : RoutingResult()

    data class NotFound(
        val path: String,
    ) : RoutingResult()

    data class MethodNotAllowed(
        val path: String,
        val allowedMethods: List<HttpMethod>,
    ) : RoutingResult()

    data class ValidationFailed(
        val missingHeaders: List<String>,
        val missingQueryParams: List<String>,
    ) : RoutingResult()
}
