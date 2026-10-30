package dev.museframework.http.api.config

/**
 * HTTP Listener lifecycle configuration.
 *
 * - [EAGER]: Server starts immediately when source.start() is called
 * - [LAZY]: Server starts on first request (handler registered immediately, server initialized lazily)
 */
sealed class HttpListenerLifecycle {
    data object EAGER : HttpListenerLifecycle()
    data object LAZY : HttpListenerLifecycle()
}
