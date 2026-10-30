package dev.museframework.e2e.packaged

import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.dsl.dsl.customCode
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.scatterGather
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.http.api.dsl.httpListener
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.springboot.MuseConfigProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

@Component
class ScatterGatherCancellationProbeConfig : MuseConfigProvider {
    override fun flows() =
        listOf(
            flow("core-scatter-gather-cancellation-probe") {
                httpListener("packaged-listener") {
                    path = "/e2e/core/scatter-gather-cancellation-probe"
                    allowedMethods = listOf(HttpMethod.POST)
                }
                customCode {
                    docName = "Reset scatter-gather cancellation probe"
                    handleErrors = false
                    code { payload ->
                        ScatterGatherCancellationProbe.reset()
                        payload
                    }
                }
                scatterGather {
                    timeout = 100
                    maxConcurrency = 2
                    route {
                        setPayload { value = "fast" }
                    }
                    route {
                        customCode {
                            docName = "Cancellable slow scatter-gather route"
                            handleErrors = false
                            code { payload ->
                                ScatterGatherCancellationProbe.markStarted()
                                try {
                                    delay(Long.MAX_VALUE)
                                    ScatterGatherCancellationProbe.markPostTimeoutActionReached()
                                    payload
                                } catch (exception: CancellationException) {
                                    ScatterGatherCancellationProbe.markCancelled()
                                    throw exception
                                }
                            }
                        }
                    }
                }
                errorHandler {
                    onErrorContinue(ErrorType.Mule.TIMEOUT) {
                        setPayload { value = "TIMEOUT" }
                    }
                }
            },
            flow("core-scatter-gather-cancellation-probe-state") {
                httpListener("packaged-listener") {
                    path = "/e2e/core/scatter-gather-cancellation-probe-state"
                    allowedMethods = listOf(HttpMethod.POST)
                }
                customCode {
                    docName = "Read scatter-gather cancellation probe"
                    code { ScatterGatherCancellationProbe.snapshot() }
                }
            },
        )
}

private object ScatterGatherCancellationProbe {
    private val started = AtomicBoolean()
    private val cancelled = AtomicBoolean()
    private val postTimeoutAction = AtomicBoolean()

    fun reset() {
        started.set(false)
        cancelled.set(false)
        postTimeoutAction.set(false)
    }

    fun markStarted() {
        started.set(true)
    }

    fun markCancelled() {
        cancelled.set(true)
    }

    fun markPostTimeoutActionReached() {
        postTimeoutAction.set(true)
    }

    fun snapshot(): String =
        "started=${started.get()},cancelled=${cancelled.get()},postTimeoutAction=${postTimeoutAction.get()}"
}
