# Maven plugin runtime targets

The Muse Maven plugin translates supported Mule XML components into Kotlin and delegates application commands to the selected host runtime. Configure the plugin and its mapper-provider dependencies in the application project.


The Maven plugin resolves runtime-specific generation and commands through a
runtime strategy. Set `muse.runtime` to `spring-boot` or `quarkus`; when it is
omitted, the plugin detects the runtime from compile/runtime dependencies.

| Runtime | Generated configuration | `muse:run` | `muse:build` | `muse:build-image` |
|---|---|---|---|---|
| Spring Boot | Spring component plus application entry point | `spring-boot:run` | `spring-boot:repackage` | `spring-boot:build-image` |
| Quarkus | CDI bean; native builds use `MuseConfigProvider` | `quarkus:dev` | `quarkus:build` | `quarkus:image-build` |

`muse:migrate` defaults to Spring Boot when no runtime can be detected. Select
Quarkus explicitly with:

```bash
mvn muse:migrate -Dmuse.runtime=quarkus
```

The Quarkus target generates a Quarkus POM, CDI configuration classes,
`application.properties`, native profile, and Jib container-image dependency.
It expects the `muse-quarkus` runtime extension and, for HTTP applications, the
`muse-http-connector-quarkus` extension. APIKit migration from Mule XML to Quarkus currently fails explicitly in
`QuarkusRuntimeStrategy` when the source contains an APIKit namespace. The
[APIKit Quarkus extension](../muse-apikit-connector-quarkus/) and
[handwritten Quarkus application tests](../examples/test-muse-quarkus/src/test/kotlin/dev/museframework/test/apikit/)
exist, but the Maven migration strategy still rejects APIKit for this target.


## Mule XML migration: verified path and known limitations

The migration input defaults to `src/main/mule`. After configuring the plugin, host, and mapper providers, the reviewed Spring Boot example used:

```bash
mvn muse:generate
mvn compile
mvn muse:run -Dmuse.skipGenerate=true
```

On 2026-11-03, direct generation wrote five files (four Kotlin sources and one resource), compilation succeeded and the Spring application started. The explicit `muse.skipGenerate` flag tells `muse:run` to use previously generated code. It does not repair delegation or translate unsupported components.

The same example exposed two pre-existing problems:

- `mvn muse:check compile` failed: 38 components checked, 17 nested elements reported unmapped. Some are child elements handled by their parent HTTP/transform mappers; review diagnostics individually before treating them as unsupported.
- Plain `mvn muse:run` failed in delegated generation with HTTP/APIKit mapper errors. Missing provider context is a hypothesis, not an established cause.

The running application returned HTTP `200` for its tested resource GET and binary POST routes. Its unknown-path GET returned `400` where the proposed acceptance contract expected `404`, and JSON POST returned `200` where `415` was expected. Those negative checks failed; no original Mule runtime baseline was captured, so behavioral parity is unproven.

Generated Kotlin under `target/generated-sources/muse` is disposable. `muse:migrate` is a distinct goal that was inspected but not executed in this migration exercise: it can back up and rewrite the project POM and emit Kotlin under `src/main/kotlin`. Review its effect before using it on an existing project.

See [the migration goal source](../muse-maven-plugin/muse-maven-plugin/src/main/kotlin/dev/museframework/plugin/maven/mojo/MuseMigrateMojo.kt) and [the runtime goal source](../muse-maven-plugin/muse-maven-plugin/src/main/kotlin/dev/museframework/plugin/maven/mojo/MuseRunMojo.kt) for current behavior.
