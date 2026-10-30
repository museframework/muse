package dev.museframework.test

import dev.museframework.http.api.config.HttpRequestConnectionConfig
import dev.museframework.http.api.dsl.httpRequest
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.http.api.model.HttpProtocol
import dev.museframework.dsl.api.config.MuseConfigDefinition
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.dsl.dsl.setVariable
import dev.museframework.dsl.api.model.flow.FlowInitialState
import dev.museframework.engine.components.processor.dsl.logger
import dev.museframework.springboot.MuseConfigComponent

@MuseConfigComponent
class File2 : MuseConfigDefinition() {
    val httpRequestConnection = HttpRequestConnectionConfig(
        host = "httpbin.dev",
        docName = "request config",
        port = 443,
        protocol = HttpProtocol.HTTPS,
        basePath = "/",
        responseTimeoutMs = 10000,
    )

    val declarativeFlow = flow("declarative-flow-2") {
        initialState = FlowInitialState.STOPPED
        logger {
            docName = "Logger"
            message = "declarative flow 2"
        }
        setPayload {
            docName = "Set Payload"
            value = "new-payload-value"
        }
        logger {
            docName = "Logger"
            message = "#[payload]"
        }
        setVariable("httpStatus") {
            docName = "Set HTTP Status"
            value = "202"
        }
        logger {
            docName = "Logger"
            message = "#[vars.httpStatus]"
        }
        httpRequest(httpRequestConnection) {
            path = "/get"
            method = HttpMethod.GET
            
        }
        logger {
            docName = "Logger"
            message = "#[payload]"
        }
    }
}
