package dev.museframework.apikit.routing

import dev.museframework.apikit.api.model.ApiMethod
import dev.museframework.http.api.model.HttpMethod
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AcceptValidatorTest {
    private val json = ApiMethod(HttpMethod.GET, responseMediaTypes = listOf("application/json"))

    @Test
    fun `accepts absent empty exact wildcard and alternative preferences`() {
        assertEquals(AcceptValidation.ACCEPTED, AcceptValidator.validate(json, emptyMap()))
        listOf(
            "", " \t", "*/*", "APPLICATION/JSON", "application/*",
            "text/plain, application/json", "application/xml;q=0, application/json;q=0.5",
            "application/json;q=0.001", "application/json;q=1.000",
            "application/json,", ",application/json", ",, application/json, ,",
            "application/json;;q=0.5", "application/json;q=0,application/json;q=1",
            "application/json;q=0.7, application/json;q=0.2",
            "application/json;Q=0.5",
        ).forEach { header ->
            assertEquals(AcceptValidation.ACCEPTED, accept(json, header), header)
        }
    }

    @Test
    fun `rejects only valid preferences that exclude every offered representation`() {
        listOf(
            "application/xml", "text/*", "application/json;q=0",
            "application/json;q=0, */*;q=1",
            "application/*;q=0, */*;q=0.8",
            "application/xml;q=1, application/json;q=0",
            "application/json;profile=other", "application/json;q=1;profile=other",
            "application/json;profile=other;q=1", "application/json;note=\"a,b\"",
        ).forEach { header ->
            assertEquals(AcceptValidation.NOT_ACCEPTABLE, accept(json, header), header)
        }
    }

    @Test
    fun `exact q zero excludes JSON but allows an independently offered XML representation`() {
        val both = ApiMethod(HttpMethod.GET, responseMediaTypes = listOf("application/json", "application/xml"))
        assertEquals(AcceptValidation.ACCEPTED, accept(both, "application/json;q=0, */*;q=1"))
        assertEquals(AcceptValidation.NOT_ACCEPTABLE, accept(both, "application/json;q=0, application/xml;q=0"))
    }

    @Test
    fun `quoted commas and multiple case variants of Accept field names are handled`() {
        assertEquals(
            AcceptValidation.ACCEPTED,
            accept(json, "application/json;profile=\"a,b\", application/json"),
        )
        assertEquals(
            AcceptValidation.ACCEPTED,
            AcceptValidator.validate(json, mapOf("ACCEPT" to "application/xml", "accept" to "application/json")),
        )
    }

    @Test
    fun `malformed preferences are not misreported as valid 406 exclusions`() {
        listOf(
            "application/json;q=2", "application/json;q=1.001", "application/json;q=0.1234",
            "application/json;q=", "application/json;q=0;q=1",
            "application/json; note=\"unterminated", "application/json; note=\"a\nb\"",
            "application/json; q =0.5",
            "application/json;note=\"unfinished\\", "application/json\r\n",
            "application/json;q=\"0.5\"", "*/json",
        ).forEach { header ->
            assertEquals(AcceptValidation.MALFORMED, accept(json, header), header)
        }
    }

    @Test
    fun `long inputs use bounded scans and explicit rejection`() {
        val longValid = "application/json; note=\"${"a".repeat(32_768)}\";q=0.9"
        assertEquals(AcceptValidation.NOT_ACCEPTABLE, accept(json, longValid)) // parameter-qualified, not bare JSON
        assertEquals(AcceptValidation.MALFORMED, accept(json, "application/json, ${"a".repeat(70_000)}"))
        assertEquals(AcceptValidation.MALFORMED, accept(json, "application/json, ".repeat(300)))
        assertEquals(AcceptValidation.MALFORMED, accept(json, ",".repeat(512)))
        assertEquals(AcceptValidation.ACCEPTED, accept(json, ",".repeat(32) + "application/json"))
    }

    @Test
    fun `unconstrained operations do not validate Accept`() {
        val noBody = ApiMethod(HttpMethod.GET)
        assertEquals(AcceptValidation.ACCEPTED, accept(noBody, "invalid accept header"))
    }

    private fun accept(method: ApiMethod, value: String): AcceptValidation =
        AcceptValidator.validate(method, mapOf("Accept" to value))
}
