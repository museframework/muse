package dev.museframework.test

import dev.museframework.apikit.api.dsl.apikit
import dev.museframework.apikit.api.dsl.apikitConfig
import dev.museframework.dsl.annotation.MuseConfig
import dev.museframework.dsl.api.config.MuseConfigDefinition
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.http.api.dsl.httpListener
import dev.museframework.http.api.model.HttpMethod
import jakarta.enterprise.context.ApplicationScoped

@MuseConfig
@ApplicationScoped
class ApikitFlow : MuseConfigDefinition() {
    val apiConfig = apikitConfig {
        name = "api-config"
        spec = "api/api-spec.raml"
    }

    val getApikitHello = flow("get:\\apikit\\hello:api-config") {
        setPayload { value = "\"hello via apikit\"" }
    }

    val postApikitHello = flow("post:\\apikit\\hello:api-config") {
        setPayload { value = "\"hello via apikit\"" }
    }
    val apikitMain = flow("apikit-main") {
        httpListener(httpListenerConfig) {
            path = "/apikit/*"
            allowedMethods = listOf(HttpMethod.GET, HttpMethod.POST)
        }
        apikit("api-config") {}
    }
}
