# ADR-019 — Provider-Owned Babelfish Verification

## Status

Accepted on 2026-08-28. Ownership cutover recorded 2026-10-06: platform,
family packaged, plugin-realm, and composition owners are active.

## Context

Babelfish is extended by component-family providers. A provider contributes Mule
XML mappers, Kotlin emitters, and a
`META-INF/services/dev.museframework.babelfish.spi.BabelfishMapperProvider`
descriptor. Core, HTTP, APIKit, and future connector families must remain
independently buildable and testable.

A central Babelfish integration-test module previously accumulated semantic
fixtures from unrelated provider families. Some tests configured registrar
objects directly, bypassing the public `BabelfishMapperProvider` extension
boundary. Adding another provider would have required new dependencies,
fixtures, and setup in that central module.

The verification architecture must assign behavior to its owning module while
still proving that independently packaged providers coexist in the Maven plugin
classloader and in packaged applications.

## Decision

Babelfish verification is owned by the component family whose contract it
exercises. There is no generic central module for provider semantic tests.

### Provider-neutral verification

`muse-babelfish` tests only provider-neutral behavior:

- XML parsing, namespaces, source locations, and malformed input;
- registry lookup and missing-mapper diagnostics;
- mapper and emitter ownership and deterministic collision diagnostics;
- generic flow, sub-flow, and error-handler mapping with fakes;
- string escaping and code-generation mechanics;
- provider-ID validation that does not require a concrete built-in provider.

The base module does not depend on concrete mapper-provider modules.

### Provider-owned verification

Each provider module owns its mapper and emitter unit tests, service descriptor,
provider contract, independent XML fixture, and semantic translation integration
test.

| Provider | Owning module |
|---|---|
| Core | `muse-babelfish-core-mappers` |
| HTTP | `muse-http-babelfish-mappers` |
| APIKit | `muse-apikit-babelfish-mappers` |
| Database | `muse-db-babelfish-mappers` |
| File | `muse-file-babelfish-mappers` |

A provider-local translation test must:

1. configure a fresh `BabelfishRegistry` through the provider;
2. translate a real family-owned XML fixture;
3. assert the typed Muse model;
4. configure a fresh `KotlinCodeEmitter` through the provider;
5. assert generated DSL and public API imports;
6. avoid dependencies on unrelated provider families.

Provider-local integration tests do not call registrar objects as a substitute
for the provider extension point.

### Provider contract

Every provider has a stable lowercase ID, a public no-argument constructor, and
stateless registration behavior. Its contract test verifies that:

- the expected implementation is discovered exactly once through
  `ServiceLoader` using the provider classloader;
- no discovered provider reuses its ID;
- the service descriptor contains one valid implementation;
- mapping and emitter registration work on multiple fresh instances;
- provider-specific options are forwarded consistently.

Tests assert discovery of the expected provider, not exclusive ownership of the
entire test classpath.

### Test boundaries and names

- `*Test` covers mapper, emitter, registry, and provider-contract unit behavior.
- `*IT` covers XML-to-model-to-Kotlin translation within one module and JVM.
- `*E2ETest` crosses a package, process, application-startup, or real transport
  boundary.

Fixtures belong to the family under test. Core fixtures do not use HTTP as
scaffolding; HTTP and APIKit fixtures do not add unrelated core components merely
to resemble complete applications.

### Plugin-realm composition

`muse-maven-plugin/muse-plugin-it` owns verification that depends on the Maven
plugin classloader for core, HTTP, APIKit, and Database. Root reactor modules
`muse-file-plugin-it`, `muse-sftp-plugin-it`, and `muse-db-plugin-it` own the
same Invoker boundary for their families (presence and intentional
missing-provider failure). They are not modules of `muse-maven-plugin`. Maven
Invoker fixtures prove:

- core-provider availability;
- visibility of optional providers declared as plugin dependencies;
- clear diagnostics for missing providers;
- provider-ID and registration collision behavior;
- successful generation from supported provider compositions.

The `all-built-in-providers` fixture is the intentional compatibility check for
coexistence of core, HTTP, APIKit, and Database. A future provider joins this
fixture only when coexistence with every built-in provider is a supported
contract. File and SFTP stay family-local and are not part of that fixture.

### Packaged application boundary

Packaged acceptance is split by ownership:

- `muse-packaged-e2e` — platform generation, Spring/HTTP packaging, policy-extension
  registration, and platform E2E (no DB/File/SFTP runtimes);
- `muse-{db,file,sftp}-packaged-e2e` — family packaged acceptance and generated
  compilation for that connector;
- `muse-connector-composition-e2e` — bounded File → SFTP → Database runtime
  composition (success, mid-transfer SFTP failure, Database failure after remote
  completion). File uses `applyPostActionWhenFailed=false`; failures propagate.
  This does not promise rollback, distributed transactions, or exactly-once
  delivery.

Local ownership tags on each owner's POM (`muse.verification.kind`,
`muse.verification.family`, optional `muse.verification.codegen`) drive discovery
via `scripts/verification-owners.py`. CI runs every discovered owner through
`scripts/run-verification-owner.py` under `-Pe2e` (packaged/platform/composition)
or `-Pplugin-it` (plugin-realm). With `-Pe2e`, Failsafe selects both `*IT` and
`*E2ETest` suites; report gates require both inventories.

Shared process helpers live in `muse-packaged-e2e-testkit` and
`muse-packaged-test-parent`. They are not verification owners.

### Shared test utilities

No `muse-babelfish-testkit` module is introduced for three providers. The common
provider assertions remain small, while mapping and emitter setup is
family-specific. Extraction is reconsidered when a fourth provider demonstrates
a stable shared contract that outweighs the cost of another module and reactor
dependency.

### Adding a connector verification family

1. Add tagged packaged and (when codegen-enabled) plugin-realm reactor modules.
2. Keep mapper providers on the Maven plugin dependency list where generation
   needs them; keep family runtimes out of `muse-packaged-e2e`.
3. Prove the family with `scripts/verification-owners.py --require-family-pairs`
   and `scripts/run-verification-owner.py` before relying on CI discovery.

## Consequences

Positive consequences:

- semantic regressions fail in the module that owns the behavior;
- optional providers remain independently buildable;
- adding a provider does not expand a central semantic-test module;
- `ServiceLoader` packaging is verified at both module and plugin boundaries;
- plugin composition and application behavior remain explicit higher-level
  contracts;
- CI report ownership follows production ownership.

Trade-offs and limitations:

- small provider-contract assertions remain duplicated;
- each provider must maintain unit, integration, descriptor, and CI wiring;
- Maven Invoker composition tests are slower than provider-local tests;
- maintainers must keep provider-local fixtures free of accidental cross-family
  dependencies.

## Rejected Alternatives

A central integration-test module was rejected because it obscures ownership,
accumulates provider dependencies, and can bypass the public extension point.

Putting all semantic coverage in Maven Invoker was rejected because feedback is
slower and failures are less local. Putting all coverage in packaged E2E tests
was rejected because it conflates mapper semantics with runtime and transport
wiring.

Introducing a shared testkit immediately was rejected as premature abstraction.

## Enforcement

A provider change is complete only when:

- focused mapper and emitter tests cover its public configuration;
- `<Family>MapperProviderTest` verifies its provider contract;
- `<Family>TranslationIT` verifies an independent family-owned fixture;
- Surefire and Failsafe reports contain actual testcase entries;
- plugin-consumable providers have appropriate Maven Invoker coverage;
- new runtime or transport behavior has packaged E2E coverage;
- production dependencies use Babelfish, Muse DSL, and the provider family's
  public API rather than its runtime implementation.
