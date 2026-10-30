package dev.museframework.test

import dev.museframework.http.api.dsl.httpListenerConnection

val httpListenerConfig = httpListenerConnection("testListener") {
    host = "0.0.0.0"
    port = 8080
    basePath = "/"
    // The Quarkus adapter registers routes on the framework-managed HTTP server.
}