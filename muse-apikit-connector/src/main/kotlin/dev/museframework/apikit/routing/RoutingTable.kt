package dev.museframework.apikit.routing

import dev.museframework.apikit.api.model.ApiSpecification
import dev.museframework.apikit.api.routing.RoutingResult
import dev.museframework.http.api.model.HttpMethod
import org.slf4j.LoggerFactory

class RoutingTable(
    private val spec: ApiSpecification,
    private val configName: String,
) {
    private val logger = LoggerFactory.getLogger(RoutingTable::class.java)
    private val routes: List<RouteEntry> = buildRoutes()

    fun resolve(
        path: String,
        method: HttpMethod,
    ): RoutingResult {
        logger.info("RoutingTable resolving path: {}, method: {}", path, method)
        val normalizedPath = normalizePath(path)

        for (route in routes) {
            val pathMatch = UriPatternMatcher.match(route.pathPattern, normalizedPath)

            if (pathMatch.matched) {
                if (route.methods.contains(method) || route.methods.contains(HttpMethod.ANY)) {
                    val flowName = buildFlowName(route, method)
                    return RoutingResult.Match(
                        flowName = flowName,
                        pathParams = pathMatch.pathParams,
                    )
                } else {
                    return RoutingResult.MethodNotAllowed(
                        path = path,
                        allowedMethods = route.methods,
                    )
                }
            }
        }

        logger.info("RoutingTable: no match for path: {}", path)
        return RoutingResult.NotFound(path = path)
    }

    private fun buildRoutes(): List<RouteEntry> =
        spec.resources.map { resource ->
            val fullPath =
                if (spec.basePath == "/") {
                    resource.path
                } else {
                    "${spec.basePath}${resource.path}"
                }

            RouteEntry(
                pathPattern = fullPath,
                methods = resource.methods.map { it.method },
            )
        }

    private fun buildFlowName(
        route: RouteEntry,
        method: HttpMethod,
    ): String {
        val pathPart = route.pathPattern.replace("/", "\\")
        return "${method.name.lowercase()}:$pathPart:$configName"
    }

    private fun normalizePath(path: String): String = if (path.startsWith("/")) path else "/$path"

    private data class RouteEntry(
        val pathPattern: String,
        val methods: List<HttpMethod>,
    )
}
