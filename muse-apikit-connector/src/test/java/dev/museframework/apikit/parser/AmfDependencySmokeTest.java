package dev.museframework.apikit.parser;

import amf.apicontract.client.platform.RAMLConfiguration;
import amf.apicontract.client.platform.model.domain.api.WebApi;
import amf.core.client.common.transform.PipelineId;
import amf.core.client.platform.model.document.Document;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies AMF dependency resolution and its JVM API against an existing migrated example. */
class AmfDependencySmokeTest {
    @Test
    void parsesAndResolvesTheRealMigratedBackendRaml() throws Exception {
        var source = Paths.get("..", "examples", "mule-test-backend", "src", "main", "resources", "api", "api-spec.raml")
                .toAbsolutePath().normalize();
        var client = RAMLConfiguration.RAML10().baseUnitClient();
        var result = client.parse(source.toUri().toString()).get();

        assertNotNull(result.baseUnit(), () -> "AMF did not parse " + source);
        var resolved = (Document) client.transform(result.baseUnit(), PipelineId.Editing()).baseUnit();
        var api = (WebApi) resolved.encodes();
        var endpoints = api.endPoints().stream().map(endpoint -> endpoint.path().value()).collect(Collectors.toSet());
        assertTrue(endpoints.contains("/api/resources"), () -> "AMF endpoints: " + endpoints);
        var resource = api.endPoints().stream().filter(endpoint -> endpoint.path().value().equals("/api/resources"))
                .findFirst().orElseThrow();
        var methods = resource.operations().stream().map(operation -> operation.method().value().toLowerCase())
                .collect(Collectors.toSet());
        assertEquals(java.util.Set.of("get", "post"), methods);
        var post = resource.operations().stream().filter(operation -> operation.method().value().equalsIgnoreCase("post"))
                .findFirst().orElseThrow();
        assertNotNull(post.request());
        assertTrue(post.request().payloads().stream().anyMatch(payload ->
                "application/octet-stream".equals(payload.mediaType().value())));
    }
}
