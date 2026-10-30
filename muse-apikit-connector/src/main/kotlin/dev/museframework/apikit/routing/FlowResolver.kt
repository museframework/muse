package dev.museframework.apikit.routing

import dev.museframework.http.api.model.HttpMethod

object FlowResolver {
    fun resolve(
        method: HttpMethod,
        path: String,
        contentType: String?,
        configName: String,
    ): String {
        val methodLower = method.name.lowercase()
        val pathAsFlowName = path.replace("/", "\\")
        return if (contentType != null) {
            val contentTypeAsFlowName = contentType.replace("/", "\\")
            "$methodLower:$pathAsFlowName:$contentTypeAsFlowName:$configName"
        } else {
            "$methodLower:$pathAsFlowName:$configName"
        }
    }
}
