# Execution Policy Migration Guide

This guide describes what RC1 changed for every consumer that executes pipelines, and what each
consumer must do instead. It replaces the previous execution model; there is **no compatibility shim,
deprecated alias or interpreter fallback** for the removed surfaces.

Customers of the change:

| Consumer | RC1 replacement | Compatibility |
|---|---|---|
| Ordinary `MuseProcessor` returning `Success`/`Failure` | Existing terminal result variants | Source shape retained; rebuild against the coordinated Engine API release |
| Router returning `SuccessWith*` | `registerPolicyRouter` + declared handles + managed `call`/`group` | Intentional source and binary break |
| Third-party router with inline bodies | `PolicyRouterDeclaration` + `definition.inline` | Capability preserved, construction contract changed |
| APIKit named routing | Declare each configured `FlowRef` at bootstrap, call handles | Missing-route semantics preserved |
| Core `FlowRef`/`Try`/`when`/`foreach`/retry/scatter/correlation | Standard policies + canonical internal owners | DSL and standard success/projection semantics preserved |
| HTTP request target | Shared `ExecutionContext.projectTarget` + terminal result | Empty deferred pipeline removed |
| Source returning a streamed reply | `executionResources` + `replyResources` + response lease | Breaking lifecycle contract; all in-repo adapters migrated together |
| Async | Managed `submit`, application ownership, new rejection errors | Intentional cancellation, bounded-admission and input-preparation changes |
| External artifact | DSL/Engine API production dependencies only | Fixture compile + packaged loading evidence |

## Binary and source breaks

The managed-policy registration is additive, but RC1 is **not** binary compatible. Removing result
subclasses and changing data-class constructors and `copy` methods are real ABI changes, and every
removed symbol below fails at compile time or link time:

- `ProcessorResult` is now exactly `Success(context)` and `Failure(error, context)`.
  `PipelineBoundary`, `SuccessWithPipeline`, `SuccessWithContext` and every other deferred/conditional
  variant are deleted. A pipeline that returned a deferred instruction must now execute and return a
  terminal result.
- `PipelineExecutor`, `ErrorHandler`, `PipelineHandler`, `TargetConfigHandler`, `IterationHandler`,
  `ParallelPipelinesHandler` and `ParallelIterationHandler` are deleted from the engine.
- The legacy `*MuseProcessor` classes of the standard routers are deleted; standard routers are
  registrations, not processors.
- `HttpRequestMuseProcessor` no longer produces a deferred empty pipeline for a target expression; it
  projects the target itself and returns a terminal result.
- `MuseEventEnvelope.replyResourceTransfer` is deleted; the reply protocol is now
  `executionResources` + `replyResources` + a retained response lease.
- `HttpResponse` carries `bodyLease`; adapters close that lease instead of releasing a provider.

`MuseComponentFactory.registerRouter` and its `RouterRegistration.Legacy` path still exist as the
public SPI for third-party *builder*-registered inline routers, but no in-repo production path uses
them: every standard and in-repo registrar registers a managed policy declaration.

Release notes for any release carrying this change must identify these breaks before a release is
selected. No automatic publication or version bump is implied by this document.

## 1. Routers returning `SuccessWith*`

Before: a router returned a result that instructed the executor to continue somewhere else, and the
executor interpreted that instruction recursively.

```kotlin
// Before
class MyRouterProcessor : MuseProcessor {
    override suspend fun process(context: ExecutionContext): ProcessorResult =
        ProcessorResult.SuccessWithPipeline(someOtherPipeline, context)
}
```

After: register a managed policy declaration and call declared bodies. The policy returns a terminal
outcome and never returns control instructions.

```kotlin
// After
factory.registerPolicyRouter(MyConfig::class, PolicyRouterDeclaration { component, definition, _ ->
    val body = definition.inline(component.pipeline)
    PolicyFactory { MyPolicy(body) }
})

class MyPolicy(private val body: BodyHandle) : ExecutionPolicy {
    override suspend fun execute(invocation: PolicyInvocation): PolicyOutcome =
        invocation.call(body)
}
```

See the [Execution Policy Extension Guide](execution-policy-extension-guide.md) for the complete
external registration example, owner/error/projection rules and token lifetimes.

## 2. APIKit named routing

Before: APIKit duplicated pipeline and handler construction per routed flow and rebuilt the selected
callee on every request.

After: `ApikitConnectorRegistrar` registers one `PolicyRouterDeclaration`. Inside `declare`, the
specification-derived routing table resolves at bootstrap and each routed flow is declared as a
prepared handle; execution selects the prepared handle, validates the matched method, adds
`pathParams` and calls it.

```kotlin
PolicyRouterDeclaration<ApikitRouter> { component, definition, context ->
    val spec = context.properties["apikit.$configName.spec"] as? ApiSpecification ?: error(...)
    val routingTable = RoutingTable(spec, configName)
    // Declared while the occurrence is still open: the factory runs at init, when a body can no
    // longer be declared. Only flows the application actually declares are declared here; a route
    // whose implementation flow is absent stays an event-time failure, it is not a bootstrap error.
    val flowHandles =
        buildMap {
            for (resource in spec.resources) {
                for (apiMethod in resource.methods) {
                    val flowName = "${apiMethod.method.name.lowercase()}:${fullPath.replace("/", "\\")}:$configName"
                    if (context.flows.containsKey(flowName)) {
                        put(flowName, definition.flow(FlowRef.ByName(flowName)))
                    }
                }
            }
        }
    PolicyFactory { ApikitRoutingPolicy(component, routingTable, spec, flowHandles, validationsEnabled) }
}
```

Rules that still hold and were preserved deliberately:

- declaring handles inside `PolicyFactory` instead of inside `declare` throws
  `Policy definition is closed` at `application.init()` — a factory may only capture handles.
- the `context.flows.containsKey(flowName)` filter is what keeps missing-route semantics: a matched
  route with no declared flow fails at event time with `APIKIT:FLOW_NOT_FOUND`, it does not become a
  bootstrap failure.
- the policy never chooses a callee's error handler and never rebuilds a pipeline.

## 3. Ordinary processors

`MuseProcessor` shape is unchanged: return `ProcessorResult.Success(context)` or
`ProcessorResult.Failure(error, context)`. Rebuild against the coordinated Engine API release (the
removed variants and the changed data classes are ABI breaks for anything compiled against the old
artifact). `MuseStackFrame`, `Pipeline` and `PipelineElement` remain unchanged.

Cancellation is delivered as coroutine cancellation and programming defects as exceptions; neither is
represented in `ProcessorResult`.

## 4. HTTP request target

Before: a target expression made the HTTP request processor return an empty deferred pipeline, and the
executor interpreted it later.

After: the processor calls the shared projection helper and maps the outcome to a terminal result.

```kotlin
// After
val outcome = context.projectTarget(PolicyOutcome.Success(context.event), targetConfig)
// map outcome to ProcessorResult.Success/Failure
```

- the projection base for an HTTP request target is the **pre-request** event;
- the target value is evaluated only on `Success`, after the callee completed;
- an expression failure is `MULE:EXPRESSION` owned by the projecting caller, with the base event;
- a provider produced by the callee (for example a streamed response body) is registered before the
  projection can discard it, and response cleanup still belongs to the adapter lease.

## 5. Sources and envelope leases

Before: a source transferred or declined a boolean `replyResourceTransfer`, and adapters released
response providers directly.

After: the envelope carries registries and a cancellation signal:

```kotlin
MuseEventEnvelope(
    event = event,
    replyTo = CompletableDeferred(),
    executionResources = StreamingResourceRegistry().also {
        StreamingEventResources.registerEventProviders(event, it)
    },
    replyResources = StreamingResourceRegistry(),        // only when the source exports providers
    requestCancellation = CompletableDeferred(),         // only when the source can signal cancellation
)
```

Protocol:

1. The source owns `executionResources` until a **successful** channel send; the engine owns it
   afterwards. A failed or cancelled send releases it, and every accepted event is released exactly
   once even when its request job never starts.
2. Before `replyTo` completes, the engine registers the final event in `executionResources` **and** in
   the still-open `replyResources`.
3. The source builds its response while that claim is alive, registers providers created by response
   expressions, retains a lease for exactly the exported provider, then closes the temporary
   `replyResources` registry.
4. The adapter closes that lease after serialization, HEAD/no-body handling, a write error or
   disconnect, or a response-construction failure. It never releases a leased provider directly.
5. No `replyResources` means the source exports no provider lifetime.

An adapter that constructs a response body outside any registry acquires an owned claim
(`StreamingResourceRegistry.ownedClaim(provider)`) instead of calling `releaseResources()`.

## 6. Request cancellation

Before: cancelling a reply waiter was indistinguishable from cancelling the request, and streamed
resources could be released while an accepted branch still needed them.

After:

- the source completes `requestCancellation` only on timeout, abort or cancellation — never on
  successful delivery;
- engine request execution watches that signal in a child watcher and cancels only that request's job;
- **accepted background work is never propagated to**: once `submit` accepted a branch, cancelling the
  originating request leaves it running (ADR-021);
- every request finalizes its claims in a non-cancellable step, so cancellation never skips cleanup or
  leaks a claim.

Cancelling a standalone `replyTo` `Deferred` in a test is no longer evidence of request cancellation;
tests must use the envelope signal or a real transport abort.

## 7. Async admission errors

Before: `async` launched work in the caller's scope without bound, could not survive its request, and
reported nothing when a stream could not outlive the request.

After: `submit` is bounded by the application's `BackgroundExecutionConfig` and by the declaration's
running limit, and it fails fast instead of admitting work it cannot own:

| Condition | `BackgroundRejection` | Standard async error |
|---|---|---|
| Application capacity exhausted | `CAPACITY` | `MULE:ASYNC_REJECTED` |
| Application is stopping | `STOPPING` | `MULE:ASYNC_REJECTED` |
| Request-bound input cannot outlive the request | `INPUT_NOT_RETAINABLE` | `MULE:ASYNC_INPUT_NOT_RETAINABLE` |

Both errors descend from `MULE:ANY` and are ordinary caller-owned domain failures: the main request
keeps its own reply, and the rejection is not a branch outcome. Defaults to plan for: 1024 outstanding
tasks, **no implicit deadline**, a 30-second drain grace before cooperative cancellation.

Request-bound non-repeatable input is promoted (spooled to the configured file-store strategy) before
acceptance, which adds acceptance latency and temporary storage in exchange for a stream that is still
readable after the main reply. Two accepted branches never gain two reads of a single-cursor provider;
a claimed or partially consumed input is rejected without rewinding it.

## 8. External artifacts

An external policy artifact must depend only on `muse-dsl`, `muse-engine-api` and its normal language
dependencies. `muse-architecture-tests` enforces that extension production code, APIKit and the neutral
HTTP runtime never depend on engine implementation packages, and the packaged fixture proves the
registrar is discovered from its own JAR on a packaged classpath.
