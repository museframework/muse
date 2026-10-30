package dev.museframework.test

import dev.museframework.http.api.dsl.httpListenerConnection

val httpListenerConfig = httpListenerConnection("testListener") {
    host = "0.0.0.0"
    port = 8080
    basePath = "/"
    // port is managed by Spring test — uses random port
}