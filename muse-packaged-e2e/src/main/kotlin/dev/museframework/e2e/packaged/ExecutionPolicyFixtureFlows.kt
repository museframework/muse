package dev.museframework.e2e.packaged

import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.dsl.PipelineOwner
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.dsl.builder.CustomCodeBuilder
import dev.museframework.dsl.builder.RaiseErrorBuilder
import dev.museframework.dsl.builder.SetPayloadBuilder
import dev.museframework.dsl.builder.SetVariableBuilder
import dev.museframework.dsl.dsl.choice
import dev.museframework.dsl.dsl.customCode
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.flowRef
import dev.museframework.dsl.dsl.forEach
import dev.museframework.dsl.dsl.raiseError
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.dsl.dsl.setVariable
import dev.museframework.dsl.dsl.subFlow
import dev.museframework.dsl.dsl.untilSuccessful
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.spi.MuseComponentRegistrar
import dev.museframework.http.api.dsl.httpListener
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.springboot.MuseConfigProvider
import dev.museframework.test.policy.DefaultOnFailureConfig
import dev.museframework.test.policy.DefaultOnFailureRegistrar
import dev.museframework.test.policy.defaultOnFailure
import org.springframework.stereotype.Component
import java.util.Collections
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Packaged fixture flows for the managed execution-policy contracts.
 *
 * Every endpoint is synthetic: the ordered trace is fixture-local test infrastructure and never a
 * runtime observer API. The external replacement endpoints run the neutral external policy that is
 * loaded from the packaged fixture JAR through its `MuseComponentRegistrar` service descriptor, so a
 * green reply also proves that registrar discovery works from the packaged classpath.
 */
@Component
class ExecutionPolicyFixtureFlows : MuseConfigProvider {
    private val nestedChildLocal =
        flow("policy-nested-child-local") {
            mark(ExecutionPolicyFixtureTrace.NESTED_LOCAL, "B-enter")
            setVariable("childMarker") {
                value = "child-local"
            }
            raiseError {
                type = "APP:B-LOCAL"
                description = "child-local"
            }
            mark(ExecutionPolicyFixtureTrace.NESTED_LOCAL, "B-tail")
            errorHandler {
                onErrorContinue(ErrorType("APP", "B-LOCAL")) {
                    mark(ExecutionPolicyFixtureTrace.NESTED_LOCAL, "B-handler")
                }
            }
        }

    private val nestedChildPropagate =
        flow("policy-nested-child-propagate") {
            mark(ExecutionPolicyFixtureTrace.NESTED_PROPAGATE, "B-enter")
            setPayload {
                value = "child-payload"
            }
            setVariable("childMarker") {
                value = "child-propagate"
            }
            raiseError {
                type = "APP:B-PROPAGATE"
                description = "child-propagate"
            }
            mark(ExecutionPolicyFixtureTrace.NESTED_PROPAGATE, "B-tail")
            errorHandler {
                onErrorPropagate(ErrorType("APP", "B-PROPAGATE")) {
                    mark(ExecutionPolicyFixtureTrace.NESTED_PROPAGATE, "B-propagate")
                    setVariable("propagated") {
                        value = "yes"
                    }
                }
            }
        }

    private val subflowChild =
        subFlow("policy-subflow-child") {
            mark(ExecutionPolicyFixtureTrace.SUBFLOW_OWNERSHIP, "subflow-body")
            raiseError {
                type = "APP:SUBFLOW"
                description = "subflow-failure"
            }
            mark(ExecutionPolicyFixtureTrace.SUBFLOW_OWNERSHIP, "subflow-tail")
        }

    private val targetRecoveryChild =
        flow("policy-target-recovery-child") {
            mark(ExecutionPolicyFixtureTrace.TARGET_RECOVERY, "child-body")
            setVariable("childMarker") {
                value = "child"
            }
            raiseError {
                type = "APP:TARGET-RECOVERY"
                description = "target-recovery"
            }
            errorHandler {
                onErrorContinue(ErrorType("APP", "TARGET-RECOVERY")) {
                    mark(ExecutionPolicyFixtureTrace.TARGET_RECOVERY, "child-handler")
                    setPayload {
                        value = "recovered-payload"
                    }
                }
            }
        }

    override fun flows(): List<MuseFlow> =
        listOf(
            nestedChildLocal,
            nestedChildPropagate,
            subflowChild,
            targetRecoveryChild,
            flow("policy-nested-local") {
                httpListener("packaged-listener") {
                    path = "/policy/nested-local"
                    allowedMethods = listOf(HttpMethod.POST)
                }
                reset(ExecutionPolicyFixtureTrace.NESTED_LOCAL)
                setVariable("processed") {
                    value = ""
                }
                forEach {
                    collection = "#[[1,2]]"
                    counterVariableName = "iteration"
                    rootMessageVariableName = "root"
                    markPayload(ExecutionPolicyFixtureTrace.NESTED_LOCAL, "item-start")
                    choice {
                        `when`("#[payload == 1]") {
                            flowRef("policy-nested-child-local") {
                                docName = "Flow B with a local continue handler"
                            }
                        }
                        otherwise {}
                    }
                    markPayload(ExecutionPolicyFixtureTrace.NESTED_LOCAL, "after-call")
                    setVariable("processed") {
                        value = "#[vars.processed ++ (payload as String)]"
                    }
                }
                mark(ExecutionPolicyFixtureTrace.NESTED_LOCAL, "outer-tail")
                setPayload {
                    value = "#[vars.childMarker ++ '|processed=' ++ vars.processed]"
                }
                errorHandler {
                    onErrorContinue(ErrorType.Mule.ANY) {
                        mark(ExecutionPolicyFixtureTrace.NESTED_LOCAL, "outer-handler")
                        setPayload {
                            value = "#[vars.childMarker ++ '|error=' ++ error.errorType.namespace ++ ':' ++ error.errorType.identifier]"
                        }
                    }
                }
            },
            flow("policy-nested-propagate") {
                httpListener("packaged-listener") {
                    path = "/policy/nested-propagate"
                    allowedMethods = listOf(HttpMethod.POST)
                }
                reset(ExecutionPolicyFixtureTrace.NESTED_PROPAGATE)
                forEach {
                    collection = "#[[1,2]]"
                    counterVariableName = "iteration"
                    rootMessageVariableName = "root"
                    markPayload(ExecutionPolicyFixtureTrace.NESTED_PROPAGATE, "item-start")
                    choice {
                        `when`("#[payload == 1]") {
                            flowRef("policy-nested-child-propagate") {
                                docName = "Flow B that propagates its failure"
                            }
                        }
                        otherwise {}
                    }
                    markPayload(ExecutionPolicyFixtureTrace.NESTED_PROPAGATE, "after-call")
                }
                mark(ExecutionPolicyFixtureTrace.NESTED_PROPAGATE, "outer-tail")
                setPayload {
                    value = "unreachable-tail"
                }
                errorHandler {
                    onErrorContinue(ErrorType.Mule.ANY) {
                        mark(ExecutionPolicyFixtureTrace.NESTED_PROPAGATE, "outer-handler")
                        setPayload {
                            value =
                                "#[payload ++ '|' ++ vars.childMarker ++ '|' ++ vars.propagated ++ '|' ++ " +
                                    "error.errorType.namespace ++ ':' ++ error.errorType.identifier ++ '|' ++ error.description]"
                        }
                    }
                }
            },
            flow("policy-retry-any") {
                httpListener("packaged-listener") {
                    path = "/policy/retry-any"
                    allowedMethods = listOf(HttpMethod.POST)
                }
                reset(ExecutionPolicyFixtureTrace.RETRY_ANY)
                untilSuccessful {
                    maxRetries = 2
                    millisBetweenRetries = 1
                    customCode {
                        docName = "Ordered retry attempt marker"
                        handleErrors = false
                        code { payload ->
                            val attempt = ExecutionPolicyFixtureTrace.nextAttempt(ExecutionPolicyFixtureTrace.RETRY_ANY)
                            ExecutionPolicyFixtureTrace.mark(ExecutionPolicyFixtureTrace.RETRY_ANY, "attempt:$attempt")
                            payload
                        }
                    }
                    raiseError {
                        type = "APP:RETRY"
                        description = "last-child-error"
                    }
                }
                mark(ExecutionPolicyFixtureTrace.RETRY_ANY, "after-retry")
                setPayload {
                    value = "retry-succeeded"
                }
                errorHandler {
                    onErrorContinue(ErrorType.Mule.ANY) {
                        mark(ExecutionPolicyFixtureTrace.RETRY_ANY, "outer-handler")
                        setPayload {
                            value = "#[payload ++ '|' ++ error.errorType.namespace ++ ':' ++ error.errorType.identifier ++ '|' ++ error.detailedDescription]"
                        }
                    }
                }
            },
            flow("policy-external-replacement") {
                httpListener("packaged-listener") {
                    path = "/policy/external-replacement"
                    allowedMethods = listOf(HttpMethod.POST)
                }
                reset(ExecutionPolicyFixtureTrace.EXTERNAL_ELIGIBLE)
                setVariable("entry") {
                    value = "entry-variable"
                }
                defaultOnFailure(externalPolicy(ExecutionPolicyFixtureTrace.EXTERNAL_ELIGIBLE, setOf(externalCallError())))
                setPayload {
                    value = "#[payload ++ '|' ++ vars.entry ++ '|mutation=' ++ (vars.bodyMutation default 'absent')]"
                }
                errorHandler {
                    onErrorContinue(ErrorType.Mule.ANY) {
                        mark(ExecutionPolicyFixtureTrace.EXTERNAL_ELIGIBLE, "outer-handler")
                        setPayload {
                            value = "#[error.errorType.namespace ++ ':' ++ error.errorType.identifier ++ '|' ++ error.description]"
                        }
                    }
                }
            },
            flow("policy-external-ineligible") {
                httpListener("packaged-listener") {
                    path = "/policy/external-ineligible"
                    allowedMethods = listOf(HttpMethod.POST)
                }
                reset(ExecutionPolicyFixtureTrace.EXTERNAL_INELIGIBLE)
                setVariable("entry") {
                    value = "entry-variable"
                }
                defaultOnFailure(externalPolicy(ExecutionPolicyFixtureTrace.EXTERNAL_INELIGIBLE, setOf(ErrorType("APP", "OTHER-CALL"))))
                setPayload {
                    value = "ineligible-replacement"
                }
                errorHandler {
                    onErrorContinue(ErrorType.Mule.ANY) {
                        mark(ExecutionPolicyFixtureTrace.EXTERNAL_INELIGIBLE, "outer-handler")
                        // The whole failed event, not the entry event: payload and variable mutations made
                        // inside the policy body survive, together with the exact typed error.
                        setPayload {
                            value =
                                "#[payload ++ '|' ++ vars.entry ++ '|' ++ vars.bodyMutation ++ '|' ++ " +
                                    "error.errorType.namespace ++ ':' ++ error.errorType.identifier ++ '|' ++ error.description]"
                        }
                    }
                }
            },
            flow("policy-subflow-ownership") {
                httpListener("packaged-listener") {
                    path = "/policy/subflow-ownership"
                    allowedMethods = listOf(HttpMethod.POST)
                }
                reset(ExecutionPolicyFixtureTrace.SUBFLOW_OWNERSHIP)
                flowRef(subflowChild) {
                    docName = "Sub-flow without an error scope of its own"
                }
                mark(ExecutionPolicyFixtureTrace.SUBFLOW_OWNERSHIP, "parent-tail")
                setPayload {
                    value = "unreachable-tail"
                }
                errorHandler {
                    onErrorContinue(ErrorType.Mule.ANY) {
                        mark(ExecutionPolicyFixtureTrace.SUBFLOW_OWNERSHIP, "parent-handler")
                        setPayload {
                            value = "#[error.errorType.namespace ++ ':' ++ error.errorType.identifier ++ '|' ++ error.description]"
                        }
                    }
                }
            },
            flow("policy-target-after-recovery") {
                httpListener("packaged-listener") {
                    path = "/policy/target-after-recovery"
                    allowedMethods = listOf(HttpMethod.POST)
                }
                reset(ExecutionPolicyFixtureTrace.TARGET_RECOVERY)
                flowRef(targetRecoveryChild) {
                    docName = "Recovered callee with a projected target"
                    targetConfig = TargetConfig("captured", "#[payload ++ ':' ++ vars.childMarker]")
                }
                mark(ExecutionPolicyFixtureTrace.TARGET_RECOVERY, "parent-after-target")
                setVariable("afterTarget") {
                    value = "after-target"
                }
                setPayload {
                    value = "#[vars.captured ++ '|' ++ vars.afterTarget]"
                }
                errorHandler {
                    onErrorContinue(ErrorType.Mule.ANY) {
                        mark(ExecutionPolicyFixtureTrace.TARGET_RECOVERY, "parent-handler")
                        setPayload {
                            value = "#[error.errorType.namespace ++ ':' ++ error.errorType.identifier ++ '|captured=' ++ (vars.captured default 'absent')]"
                        }
                    }
                }
            },
            flow("policy-fixture-trace") {
                httpListener("packaged-listener") {
                    path = "/policy/fixture/trace"
                    allowedMethods = listOf(HttpMethod.GET)
                }
                customCode {
                    docName = "Report the ordered execution-policy fixture trace"
                    handleErrors = false
                    code { _ -> ExecutionPolicyFixtureTrace.snapshotAll() }
                }
            },
            flow("policy-extension-source") {
                httpListener("packaged-listener") {
                    path = "/policy/extension/source"
                    allowedMethods = listOf(HttpMethod.GET)
                }
                customCode {
                    docName = "Report where the external policy fixture was loaded from"
                    handleErrors = false
                    code { _ -> externalPolicySourceReport() }
                }
            },
        )
}

private fun externalCallError(): ErrorType = ErrorType("APP", "EXTERNAL-CALL", ErrorType.Mule.ANY)

private fun externalPolicy(
    scenario: String,
    eligibleErrorTypes: Set<ErrorType>,
): DefaultOnFailureConfig =
    DefaultOnFailureConfig(
        body =
            listOf(
                CustomCodeBuilder()
                    .apply {
                        docName = "External policy body attempt"
                        handleErrors = false
                        code { payload ->
                            ExecutionPolicyFixtureTrace.mark(scenario, "policy-body-attempt")
                            payload
                        }
                    }.build(),
                SetVariableBuilder("bodyMutation")
                    .apply {
                        docName = "External policy body variable mutation"
                        value = "body-mutated"
                    }.build(),
                SetPayloadBuilder()
                    .apply {
                        docName = "External policy body payload mutation"
                        value = "policy-body-payload"
                    }.build(),
                RaiseErrorBuilder()
                    .apply {
                        docName = "External policy call failure"
                        type = "APP:EXTERNAL-CALL"
                        description = "external call failed"
                    }.build(),
            ),
        eligibleErrorTypes = eligibleErrorTypes,
        replacement = TypedValue.of("external-replacement" as Any),
        docName = "External default-on-failure policy",
        docId = "policy-external-default-on-failure",
    )

private fun externalPolicySourceReport(): String {
    val registrars =
        ServiceLoader
            .load(MuseComponentRegistrar::class.java)
            .map { it.javaClass.name }
            .sorted()
    return "registrars=${registrars.joinToString(",")}" +
        ";registrarSource=${codeSourceOf(DefaultOnFailureRegistrar::class.java)}" +
        ";configSource=${codeSourceOf(DefaultOnFailureConfig::class.java)}"
}

private fun codeSourceOf(type: Class<*>): String =
    runCatching { type.protectionDomain?.codeSource?.location?.toExternalForm() ?: "missing" }
        .getOrElse { "unavailable:${it.javaClass.simpleName}" }

private fun PipelineOwner.mark(
    scenario: String,
    marker: String,
) {
    customCode {
        docName = "Policy fixture marker $scenario/$marker"
        handleErrors = false
        code { payload ->
            ExecutionPolicyFixtureTrace.mark(scenario, marker)
            payload
        }
    }
}

private fun PipelineOwner.markPayload(
    scenario: String,
    marker: String,
) {
    customCode {
        docName = "Policy fixture payload marker $scenario/$marker"
        handleErrors = false
        code { payload ->
            ExecutionPolicyFixtureTrace.mark(scenario, "$marker:$payload")
            payload
        }
    }
}

private fun PipelineOwner.reset(scenario: String) {
    customCode {
        docName = "Reset the policy fixture trace for $scenario"
        handleErrors = false
        code { payload ->
            ExecutionPolicyFixtureTrace.reset(scenario)
            payload
        }
    }
}

/**
 * Fixture-local ordered markers, one sequence per scenario. This is synthetic test infrastructure:
 * it is reset by the endpoint under test and read back through the fixture trace endpoint.
 */
internal object ExecutionPolicyFixtureTrace {
    const val NESTED_LOCAL = "nested-local"
    const val NESTED_PROPAGATE = "nested-propagate"
    const val RETRY_ANY = "retry-any"
    const val EXTERNAL_ELIGIBLE = "external-eligible"
    const val EXTERNAL_INELIGIBLE = "external-ineligible"
    const val SUBFLOW_OWNERSHIP = "subflow-ownership"
    const val TARGET_RECOVERY = "target-recovery"

    val SCENARIOS =
        listOf(
            NESTED_LOCAL,
            NESTED_PROPAGATE,
            RETRY_ANY,
            EXTERNAL_ELIGIBLE,
            EXTERNAL_INELIGIBLE,
            SUBFLOW_OWNERSHIP,
            TARGET_RECOVERY,
        )

    private val traces = ConcurrentHashMap<String, MutableList<String>>()
    private val attempts = ConcurrentHashMap<String, AtomicInteger>()

    fun reset(scenario: String) {
        traces[scenario] = Collections.synchronizedList(mutableListOf())
        attempts[scenario] = AtomicInteger()
    }

    fun mark(
        scenario: String,
        marker: String,
    ) {
        traces.computeIfAbsent(scenario) { Collections.synchronizedList(mutableListOf()) }.add(marker)
    }

    fun nextAttempt(scenario: String): Int = attempts.computeIfAbsent(scenario) { AtomicInteger() }.incrementAndGet()

    fun snapshot(scenario: String): String = traces[scenario]?.joinToString("|") ?: ""

    fun snapshotAll(): String = SCENARIOS.joinToString(";") { "$it=${snapshot(it)}" }
}
