package dev.museframework.connectors.http.spring.fuzz

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.Strictness
import dev.museframework.dsl.api.expression.DwExpression
import dev.museframework.dsl.api.model.config.MuseConfiguration
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.dsl.dsl.choice
import dev.museframework.dsl.dsl.customCode
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.raiseError
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.dsl.dsl.setVariable
import dev.museframework.dsl.dsl.transform
import dev.museframework.http.api.dsl.httpListener
import dev.museframework.http.api.dsl.httpListenerConnection
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.springboot.MuseConfigProvider
import org.springframework.boot.test.context.TestConfiguration

/** Test-only application configuration for the Spring HTTP listener fuzz suite. */
@TestConfiguration(proxyBeanMethods = false)
class FuzzTestConfig : MuseConfigProvider {
    private val strictJsonParser =
        GsonBuilder()
            .setStrictness(Strictness.STRICT)
            .create()

    private val connection =
        httpListenerConnection("fuzz_test_connection") {
            host = "0.0.0.0"
        }

    private val fuzzFlow =
        flow("fuzz-test-flow") {
            httpListener(connection) {
                path = "/api/fuzz"
                allowedMethods = listOf(HttpMethod.POST)
                successResponse {
                    statusCode = "#[vars.httpStatus default 200]"
                }
            }

            customCode {
                docName = "Validate JSON Object"
                code { payload -> validateJsonObject(payload) }
            }

            choice {
                docName = "Validate Request"
                `when`("#[payload.valid]") {
                    setPayload {
                        docName = "Use Parsed Object"
                        value = "#[payload.body]"
                    }

                    setVariable("extractedField") {
                        docName = "Extract Field"
                        value = "#[payload.field default 'not-found']"
                    }

                    setPayload {
                        docName = "Return Extracted Field"
                        value = "#[vars.extractedField]"
                    }

                    setVariable("httpStatus") {
                        docName = "Set HTTP Status"
                        value = "200"
                    }
                }
                otherwise {
                    raiseError {
                        docName = "Reject Invalid Request"
                        type = INVALID_REQUEST_ERROR.toString()
                        description = "Request body must be a valid JSON object"
                    }
                }
            }

            errorHandler {
                onErrorContinue(INVALID_REQUEST_ERROR) {
                    transform {
                        docName = "Invalid Request Response"
                        setPayload = DwExpression.Inline("\"APP:INVALID_REQUEST\"")
                        setVariable("httpStatus", DwExpression.Inline("400"))
                    }
                }
            }
        }

    override fun flows(): List<MuseFlow> = listOf(fuzzFlow)

    override fun configs(): List<MuseConfiguration> = emptyList()

    private fun validateJsonObject(payload: Any?): Map<String, Any?> {
        if (payload !is String) return invalidPayload()

        return try {
            val json = strictJsonParser.fromJson(payload, JsonElement::class.java)
            if (json?.isJsonObject == true) {
                mapOf(
                    "valid" to true,
                    "body" to strictJsonParser.fromJson(json, Map::class.java),
                )
            } else {
                invalidPayload()
            }
        } catch (_: JsonParseException) {
            invalidPayload()
        }
    }

    private fun invalidPayload(): Map<String, Any?> = mapOf("valid" to false)

    private companion object {
        val INVALID_REQUEST_ERROR = ErrorType("APP", "INVALID_REQUEST")
    }
}
