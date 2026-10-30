package dev.museframework.connectors.http.spring

import dev.museframework.http.api.config.HttpRequestConnectionConfig
import dev.museframework.http.api.processor.HttpClient
import dev.museframework.http.testkit.HttpClientContract

class SpringHttpClientContractIT : HttpClientContract() {
    override fun createClient(config: HttpRequestConnectionConfig): HttpClient = SpringHttpClient(config)
}
