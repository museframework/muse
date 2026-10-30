package dev.museframework.test

import dev.museframework.http.api.dsl.httpListener
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.dsl.api.config.MuseConfigDefinition
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.dsl.dsl.setVariable
import dev.museframework.springboot.MuseConfigComponent

@MuseConfigComponent
class Listener(private val file2: File2) : MuseConfigDefinition() {
    val declarativeFlow = flow("hello-flow") {
        httpListener(httpListenerConfig) {
            path = "/api/hello"
            allowedMethods = listOf(HttpMethod.GET, HttpMethod.POST)
            successResponse {
                statusCode = "#[vars.httpStatus default 200]"
            }
        }
        setVariable("httpStatus") {
            docName = "Set HTTP Status"
            value = "200"
        }
        setPayload { value = "\"hello world\"" }
    }
}
