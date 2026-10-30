package dev.museframework.test.nativeapp

import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.dsl.dsl.setVariable
import dev.museframework.http.api.dsl.httpListener
import dev.museframework.http.api.dsl.httpListenerConnection
import dev.museframework.http.api.model.HttpMethod
import org.springframework.stereotype.Component

@Component
class SimpleFlow {
    private val httpListenerConfig =
        httpListenerConnection("httpConfig") {
            host = "0.0.0.0"
            port = 8080
            basePath = "/"
        }

    val helloFlow =
        flow("hello-flow") {
            httpListener(httpListenerConfig) {
                path = "/api/hello"
                allowedMethods = listOf(HttpMethod.GET, HttpMethod.POST)
                successResponse {
                    statusCode = "200"
                }
            }
            setVariable("greeting") {
                value = "\"Hello from Muse Native!\""
            }
            setPayload { value = "#[vars.greeting]" }
        }
}
