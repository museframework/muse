package dev.museframework.e2e.packaged

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Packaged process/transport proof for the cross-boundary execution-policy contracts.
 *
 * Every assertion runs against the packaged application in its own JVM and over real HTTP. The
 * ordered traces are fixture-local markers reset by the endpoint under test; they are not a runtime
 * observer API.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PackagedExecutionPolicyE2ETest : PackagedApplicationProcessSupport() {
    override val instanceId: String = "execution-policy"

    override fun applicationArguments(tempDirectory: Path): List<String> =
        listOf(
            "--muse.streaming.strategy=repeatable-file-store",
            "--muse.streaming.file-store.in-memory-size-bytes=4",
            "--muse.streaming.file-store.temp-directory=$tempDirectory",
            "--muse.streaming.file-store.delete-files-on-close=true",
        )

    @Test
    fun `packaged foreach choice flow reference local continue keeps the failed tail skipped and the outer handler idle`() {
        val response = postText("/policy/nested-local", "input")

        assertEquals(200, response.statusCode())
        assertEquals("child-local|processed=12", response.body().decodeToString())
        assertEquals(
            "item-start:1|B-enter|B-handler|after-call:1|item-start:2|after-call:2|outer-tail",
            traceOf(ExecutionPolicyFixtureTrace.NESTED_LOCAL),
        )
    }

    @Test
    fun `packaged nested propagated flow failure retains the failed item mutations and runs the outer handler once`() {
        val response = postText("/policy/nested-propagate", "input")

        assertEquals(200, response.statusCode())
        assertEquals(
            "child-payload|child-propagate|yes|APP:B-PROPAGATE|child-propagate",
            response.body().decodeToString(),
        )
        assertEquals(
            "item-start:1|B-enter|B-propagate|outer-handler",
            traceOf(ExecutionPolicyFixtureTrace.NESTED_PROPAGATE),
        )
    }

    @Test
    fun `packaged retry under an outer any handler exhausts once after every attempt`() {
        val response = postText("/policy/retry-any", "input")

        assertEquals(200, response.statusCode())
        assertEquals("input|MULE:RETRY_EXHAUSTED|last-child-error", response.body().decodeToString())
        assertEquals(
            "attempt:1|attempt:2|attempt:3|outer-handler",
            traceOf(ExecutionPolicyFixtureTrace.RETRY_ANY),
        )
    }

    @Test
    fun `packaged external policy replaces an eligible failure without entering the outer handler`() {
        val response = postText("/policy/external-replacement", "input")

        assertEquals(200, response.statusCode())
        // The replacement uses the entry event: the entry variable survives while the body's own
        // payload and variable mutations do not, and the outer handler never runs.
        assertEquals("external-replacement|entry-variable|mutation=absent", response.body().decodeToString())
        assertEquals("policy-body-attempt", traceOf(ExecutionPolicyFixtureTrace.EXTERNAL_ELIGIBLE))
    }

    @Test
    fun `packaged external policy propagates an ineligible failure to the outer handler`() {
        val response = postText("/policy/external-ineligible", "input")

        assertEquals(200, response.statusCode())
        // The whole failed event leaves the policy: the payload and the variable mutated inside the
        // policy body, the entry variable, and the exact error type and description.
        assertEquals(
            "policy-body-payload|entry-variable|body-mutated|APP:EXTERNAL-CALL|external call failed",
            response.body().decodeToString(),
        )
        assertEquals(
            "policy-body-attempt|outer-handler",
            traceOf(ExecutionPolicyFixtureTrace.EXTERNAL_INELIGIBLE),
        )
    }

    @Test
    fun `packaged sub-flow failure is owned by the calling flow once`() {
        val response = postText("/policy/subflow-ownership", "input")

        assertEquals(200, response.statusCode())
        assertEquals("APP:SUBFLOW|subflow-failure", response.body().decodeToString())
        assertEquals(
            "subflow-body|parent-handler",
            traceOf(ExecutionPolicyFixtureTrace.SUBFLOW_OWNERSHIP),
        )
    }

    @Test
    fun `packaged target projection runs on the recovered callee event and the caller continues`() {
        val response = postText("/policy/target-after-recovery", "input")

        assertEquals(200, response.statusCode())
        assertEquals("recovered-payload:child|after-target", response.body().decodeToString())
        assertEquals(
            "child-body|child-handler|parent-after-target",
            traceOf(ExecutionPolicyFixtureTrace.TARGET_RECOVERY),
        )
    }

    @Test
    fun `packaged application loads the external policy registrar from the fixture jar`() {
        val response = get("/policy/extension/source", Duration.ofSeconds(5))

        assertEquals(200, response.statusCode())
        val report = response.body().decodeToString()
        assertTrue(
            "dev.museframework.test.policy.DefaultOnFailureRegistrar" in report,
            "The external registrar must be discovered on the packaged classpath: $report",
        )
        assertTrue(
            "muse-policy-extension-fixture" in report,
            "The external policy must be loaded from the packaged fixture JAR: $report",
        )
    }

    private fun traceOf(scenario: String): String {
        val response = get("/policy/fixture/trace", Duration.ofSeconds(5))

        assertEquals(200, response.statusCode())
        val traces = response.body().decodeToString().split(';')
        val trace = traces.firstOrNull { it.startsWith("$scenario=") }
        assertTrue(trace != null, "The fixture trace for $scenario is missing: $traces")
        return trace.substringAfter('=')
    }
}
