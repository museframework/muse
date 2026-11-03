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


The migration source directory defaults to `src/main/mule`. In a configured project, `mvn muse:check` reports mapped and unmapped components; `mvn muse:run` regenerates Kotlin and starts the selected host. Mapping coverage is not proof of behavioral equivalence. Generated Kotlin under `target/generated-sources/muse` should be treated as disposable.
