# Execution Policy Extension Guide

A **managed execution policy** is an extension-defined algorithm that runs declarative pipeline
elements under engine-managed ownership. The engine owns body traversal, handler dispatch, token
validation, jobs, resource claims and registries; the policy owns only the algorithm.

The external surface lives in `muse-engine-api`:

| Package | Types |
|---|---|
| `dev.museframework.engine.api.execution.policy` | `ExecutionPolicy`, `PolicyInvocation`, `PolicyOutcome`, `PolicyFactory`, `PolicyRouterDeclaration`, `PolicyDefinition`, `BodyHandle`, `PolicyGroup`, `PolicyTask`, `PolicyCompletion`, `BackgroundHandle`, `BackgroundAdmission`, `BackgroundRejection`, `BackgroundTask`, `BackgroundExecutionConfig` |
| `dev.museframework.engine.api.spi` | `MuseComponentFactory.registerPolicyRouter`, `MuseComponentRegistrar`, `MuseContext` |
| `dev.museframework.engine.api.execution` | `ExecutionContext.projectTarget` (for connectors), `ProcessorResult`, `TargetProjection` |

Three phases, in order:

```text
bootstrap  declaration   PolicyRouterDeclaration.declare  → validates config, declares bodies/handler/background
init       preparation   PolicyFactory.create              → called once per prepared occurrence
runtime    invocation    ExecutionPolicy.execute           → once per request, invocation-local
```

## 1. Complete external registration example

This is the fixture used by the packaged boundary tests
(`muse-engine/src/test/fixtures/policy-extension`); its production dependencies are only `muse-dsl`,
`muse-engine-api` and `kotlin-stdlib`.

```kotlin
package dev.museframework.test.policy

import dev.museframework.dsl.api.dsl.PipelineOwner
import dev.museframework.dsl.api.model.component.PipelineElement
import dev.museframework.dsl.api.model.component.Router
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.policy.*
import dev.museframework.engine.api.spi.MuseComponentFactory
import dev.museframework.engine.api.spi.MuseComponentRegistrar

// 1. Component configuration: a declarative Router carrying PipelineElements, not a processor implementation.
data class DefaultOnFailureConfig(
    val body: List<PipelineElement>,
    val eligibleErrorTypes: Set<ErrorType>,
    val replacement: TypedValue<Any>,
    override val docName: String,
    override val docId: String,
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
) : Router

// 2. The algorithm. Handles and immutable configuration only; no context, scope, job or registry.
class DefaultOnFailurePolicy(
    private val body: BodyHandle,
    private val eligible: Set<ErrorType>,
    private val replacement: TypedValue<Any>,
) : ExecutionPolicy {
    override suspend fun execute(invocation: PolicyInvocation): PolicyOutcome =
        when (val outcome = invocation.call(body)) {
            is PolicyOutcome.Success -> outcome
            is PolicyOutcome.Failure ->
                if (outcome.error.errorType in eligible) {
                    PolicyOutcome.Success(invocation.input.copy(payload = replacement))
                } else {
                    outcome
                }
        }
}

// 3. The declaration: validate, declare every potential body, return a factory.
class DefaultOnFailureRegistrar : MuseComponentRegistrar {
    override fun register(factory: MuseComponentFactory) {
        factory.registerPolicyRouter(
            DefaultOnFailureConfig::class,
            PolicyRouterDeclaration { component, definition, _ ->
                require(component.eligibleErrorTypes.isNotEmpty())
                val body = definition.inline(component.body)
                val eligible = component.eligibleErrorTypes.toSet()
                PolicyFactory { DefaultOnFailurePolicy(body, eligible, component.replacement) }
            },
        )
    }
}

// 4. The DSL entry point users write in a flow body.
fun PipelineOwner.defaultOnFailure(config: DefaultOnFailureConfig) {
    router(config)
}
```

Expose the registrar through the existing service descriptor
`src/main/resources/META-INF/services/dev.museframework.engine.api.spi.MuseComponentRegistrar`:

```text
dev.museframework.test.policy.DefaultOnFailureRegistrar
```

Registrars are discovered with `ServiceLoader.load(MuseComponentRegistrar::class.java)` on the
application classpath, and Spring-managed `MuseComponentRegistrar` beans are registered as well. In a
packaged Spring Boot application the fixture JAR's descriptor is discovered from inside the packaged
classpath — that is what the `muse-packaged-e2e` fixture proves on every build.

Semantics of this example, stated exactly because the engine cannot infer them:

- eligibility is **exact error-type membership** (`ErrorType` is a data class: namespace, identifier
  and parent must all match the value the producer raised). Hierarchy matching is not implied.
- one body is attempted; the failed tail is skipped.
- an eligible failure becomes a Success whose event is the **entry event** with the configured
  replacement payload; the engine restores the incoming ambient error on that Success.
- an ineligible failure propagates unchanged, including its full failed event.
- a `Try` declared inside `body` still applies its own handler before this policy sees the outcome.
- the replacement value must be immutable (or safely shared): the engine does not deep-copy payload
  objects.

## 2. Declaration rules (`declare`)

`declare` runs at bootstrap, after shared configurations are initialized, and receives the public
`MuseContext`. It **validates and declares only**: it must not execute a body, open an event stream or
start work.

- `definition.inline(elements)` records a declarative body and recursively discovers registered nested
  routers, including ones inside unused branches.
- `definition.flow(reference)` resolves a typed `FlowRef.ByReference`/`ByName` at bootstrap. A bad
  reference inside even an unvisited body fails before any source starts; valid recursive named
  declarations resolve through reference cells instead of being constructed forever.
- `definition.localHandler(handler)` associates **one** handler with the entire algorithm rather than
  with individual calls. It is optional and callable at most once; its absence is transparent (it does
  not inherit the caller's handler as a new local owner). Its strategies' bodies are declared too.
- `definition.background(maxConcurrency, timeoutMillis)` declares the binding's application-owned
  background work: one shared running limit across invocations of this prepared operator and an
  optional deadline measured from acceptance (including permit wait). Non-positive values are rejected
  at bootstrap.
- Register each configured alternative explicitly; do not look up a string per request.

Declaring a handle inside the `PolicyFactory` instead of inside `declare` fails at `init()` with
`Policy definition is closed`: a factory may only capture handles, never create them late.

Declarations form a binding table, not a second executable representation: executable bodies remain
`Pipeline` of `PipelineElement`.

## 3. Invocation contract

`PolicyInvocation` is **invocation-local**. Retaining it, using it after `execute` returned, or using
it from another coroutine is invalid.

| Member | Contract |
|---|---|
| `input: MuseEvent` | The event this invocation started from (also the default argument of every member). |
| `call(body, event = input, timeoutMillis = null)` | Runs a declared body transparently. Returns a terminal `PolicyOutcome`. A non-positive timeout is rejected. The timeout belongs to that exact timer; a parent/nested timeout is never renamed into a body timeout. |
| `pause(millis)` | Suspends this invocation; negative values are rejected. |
| `group(maxConcurrency = null) { … }` | Request-owned structured work. `start` creates a task, `await`/`awaitAny` observe outcomes, `cancelAndJoin` finalizes. Child local owners complete inside the child; a modeled `Failure` is a value and does not cancel siblings; an unexpected defect fails the group. On group return (normally or exceptionally) remaining tasks are cancelled and joined before the value/exception leaves. |
| `project(outcome, target, base = input)` | Target projection; see section 4. |
| `manage(provider)` | Registers a policy-created provider with the invocation's execution registry immediately, before anything else can fail. |
| `submit(background, body, event = input)` | Background admission; see section 6. |

`PolicyOutcome` is terminal: `Success(event)` or `Failure(error, event)`. There is no deferred
instruction, boundary, owner token or recovery flag, and none may be added.

Rules the engine enforces and a policy must respect:

- **No caller-handler reentry.** A transparent `call` never invokes the caller's handler; the child's
  own local owner (its `Flow`/`Try`/`localHandler`) applies first. A policy cannot invoke a caller's
  handler or bypass an inner scope's handler.
- **Ambient error restoration.** At every managed return a `Success` restores the call's incoming
  ambient error; a `Failure` sets `event.error` to its error.
- **Cancellation and defects are exceptional.** A policy must not catch `CancellationException` or
  arbitrary programming defects to synthesize a domain Success. Cancellation is not a flow-control
  value.
- **Policy-defined success is legitimate** when it is the declared criterion of the composed operation
  (for example an eligible replacement). The policy must document its success criterion, eligible
  modeled errors, replacement validity and event-mutation behaviour.
- **Preserve the event.** Failed events keep payload, attributes, variables, correlation id and source
  metadata, subject only to the documented projection rules.

## 4. Owner, error and projection rules

Execution order around one owner:

```text
owner entry
  entire algorithm
    canonical calls to transparent bodies
    nested owners complete locally
  algorithm's terminal outcome
  this owner's local strategy, only on final Failure
owner completion and metadata restoration
caller resumes after the completed scope
```

- Classification is by result variant, never by the presence of `event.error`.
- Each owner keeps private invocation-local active-handler state; there is no global handler blacklist.
- A propagate strategy that succeeds leaves the owner with the original error plus the full
  handler-mutated event; a strategy body that fails leaves a new `Failure` without reentry; a strategy
  predicate failure is `MULE:EXPRESSION` with the original error in child diagnostics.

`project` evaluates a target expression **only on `Success`, after the called body/owner completed**:

- `Failure` is returned unchanged (the target expression never runs).
- The projection writes `base.copy(variables = base.variables + (target.target to value))`; when the
  target expression is absent the value is the outcome payload.
- A target expression failure is `MULE:EXPRESSION` owned by the **projecting caller**, with `base` as
  the failure event; it is never attributed to the completed callee.
- Providers created (or returned) by the projection are registered before the operation can discard or
  throw them.

Standard projection bases, unchanged by the policy seam:

| Operation | Success base (no target) | Target projection base | Unhandled failure event |
|---|---|---|---|
| Flow reference | Full callee event | Pre-call event | Full callee/handler-mutated event |
| Choice | Selected body event | No built-in target | Full selected-body event |
| Sequential foreach | Original payload/attributes, sequential variable updates | No built-in target | Full failing item event, no later item |
| Parallel foreach | Ordered list of item events | Pre-iteration event | Entry event plus composite error with indexed failures/results |
| Scatter-gather | Ordered route events, merged route variables | Entry payload/attributes | Entry event plus composite error; owned timeout uses the entry event |
| Retry | Successful attempt event | No built-in target | Entry event plus `MULE:RETRY_EXHAUSTED` containing the last child error |
| Correlation scope | Body event, outer correlation id restored | No built-in target | Failed event with the caller's correlation restored |
| Async | Immediate original event, no child result merge | No built-in target | Admission `Failure` only; accepted-branch termination is separate |

## 5. Token, handle and object lifetimes

| Object | Lifetime | Notes |
|---|---|---|
| `BodyHandle` | The owning prepared binding's lifetime | Reusable across invocations of that binding; unusable by another application/binding or after shutdown. |
| `BackgroundHandle` | The owning prepared binding's lifetime | Engine-issued for exactly one declaration; a handle from another binding is a contract defect. |
| `PolicyInvocation` | One `execute` call | Expires when `execute` returns; never retain it. |
| `PolicyGroup` | The `group { }` block | Its tasks are valid only while it is open. |
| `PolicyTask` | The owning group | Awaiting a task of another group, a cancelled token or a completed token that was already removed is a contract defect. `awaitAny` requires a nonempty list of distinct tokens of the open group. |
| `BackgroundTask` | Longer than the submitting request by design | Exposes `cancel()` and `join()` only; it retains no branch outcome, event or failure graph and is not a way to recover a closed invocation. |
| Provider claims | Until the last claim closes | A provider's `close()`/`releaseResources()` runs only at last-owner cleanup and never while another registry owns it. |

Foreign, forged, expired or already-closed handles/tokens are **contract defects**, not eligible
domain failures: they are reported as `IllegalArgumentException`/`IllegalStateException`, and a policy
must not convert them into a modeled `Failure` it then "recovers".

## 6. Background work

```kotlin
data class BackgroundExecutionConfig(
    val maxOutstandingTasks: Int = 1024,
    val defaultTimeoutMillis: Long? = null,   // no implicit deadline
    val shutdownGraceMillis: Long = 30_000,
)
```

- Capacity counts **queued and running** accepted tasks application-wide; a slot is released only after
  the task's structured descendants and provider cleanup finish.
- A declaration's `timeoutMillis` (or the configured default) is measured from acceptance, including
  permit wait; it is not a per-item request timeout.
- `submit` is a suspend operation because request-bound input may need preparation:

  | Step | Effect |
  |---|---|
  | liveness/handle validation | invocation-liveness, caller-cancellation and provenance checks |
  | capacity reservation | one slot reserved while admission is open; nothing published |
  | claim + preparation | the event's resource graph is retained in a branch registry; transport-bound providers are promoted for background use |
  | publication | the linearization point: open state and caller cancellation are checked under the admission lock and the task record is published; no suspension occurs here |
  | scheduling | the already-owned job starts under the application supervisor; a scheduling failure still finalizes the accepted task |
  | return | `BackgroundAdmission.Accepted(task)` without further suspension; a racing cancellation cannot withdraw the task |

- A rejection is a normal caller-owned domain failure of the submitting invocation and never rewrites
  an accepted branch: `CAPACITY`/`STOPPING` → `MULE:ASYNC_REJECTED`,
  `INPUT_NOT_RETAINABLE` → `MULE:ASYNC_INPUT_NOT_RETAINABLE` (both children of `MULE:ANY` in the
  standard async policy).
- An **accepted** branch survives cancellation of its originating request (ADR-021); it is cancelled by
  explicit `BackgroundTask.cancel()`, a configured deadline, or application shutdown.
- Terminal handling emits exactly one engine-logger diagnostic per branch ending (modeled failure, or
  defect/timeout with the configured classification), carrying task, correlation and component ids and
  never payload or full-event capture. This is ordinary async terminal handling, not an observer SPI,
  audit log or diagnostic queue.
- `stop()` closes admission, stops sources, drains queued events, waits the configured grace for
  accepted work, then cancels and joins what is unfinished before releasing application resources.
  Grace is a wait **before** cancellation, not a hard bound; repeated stop is idempotent.

## 7. Thread-safety obligations

`execute` may run concurrently for the same prepared binding (one instance per prepared occurrence is
shared by all requests), so:

- factories may capture only **immutable handles and immutable configuration**;
- per-request attempt state, task tokens and outcome collections must be **local variables inside
  `execute`** — never fields of the policy instance;
- declared limits (`background(maxConcurrency)`, `group(maxConcurrency)`) are shared across requests
  and enforce a real ceiling, so a policy must not assume exclusive access to a body;
- never hold a lock of your own while calling `call`/`group`/`submit`/`pause`: child bodies are engine
  code and may complete other work;
- register policy-created providers through `manage` before any operation that can fail, and never call
  `close()`/`releaseResources()` on a provider an engine registry owns (`isManaged` is the mechanical
  rule: if it reports `true`, release the registry or lease instead);
- the engine does not deep-copy payload objects or sandbox Kotlin: do not retain invocation objects, do
  not mutate shared configured values, and do not assume the engine protects you from a payload object
  mutated concurrently by user code.

Resource claims protect **lifetime**, not thread safety or snapshot semantics of mutable user objects.

## 8. Resource claims and transport lifetime

- A provider may be owned by the root request, an accepted branch and a response at the same time. Each
  owner holds an independent claim; `close()`/`releaseResources()` runs only at the last claim, so the
  main response completing never closes a stream a live branch still reads (ADR-020).
- `MuseEventEnvelope.executionResources` covers the event while queued and while executing;
  `replyResources` carries a source's exported provider; the adapter closes the response lease.
- Request-bound non-repeatable input is promoted for background use before acceptance (spooled into the
  configured file-store strategy). If independence cannot be established — the cursor was already
  claimed, a preparation overlapped, the transport is gone — admission returns
  `INPUT_NOT_RETAINABLE` instead of silently rewinding the input.

See the [Streaming Guide](streaming-guide.md) for the strategy and lease details, and the
[Execution Policy Migration Guide](execution-policy-migration.md) for the compatibility breaks that
accompany this contract.
