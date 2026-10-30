---
status: accepted
date: 2026-09-09
---

# ADR-021 — Async Work Survives Originating Request Cancellation

Once an explicit async branch has been accepted, cancellation of its originating request must not cancel that branch. Acceptance transfers responsibility for the remaining work beyond the request lifetime, so fire-and-forget work does not depend on the client keeping a connection or request alive. This is the user's Q4 decision in the execution-policy refinement; it complements, rather than follows automatically from, the streaming lifetime requirement in ADR-020.

## Consequences

- Request cancellation after acceptance is not a termination signal for the accepted branch, whether the main response has already been produced or not.
- Responsibility for accepted work must be retained outside the request lifetime. This decision does not prescribe particular coroutine classes, parent job construction, or a public task registry.
- Acceptance must have a well-defined handoff point. The exact admission/cancellation race, capacity rules, and resource acquisition mechanism remain design work; a branch must not be acknowledged as accepted while its ownership can still be lost with the request.
- Cancellation of ordinary request-owned work is unchanged. Explicit branch cancellation, deadlines, and application shutdown are separate concerns whose defaults and drain rules remain undecided.
- Accepted work is not promised durable execution across process failure, and this decision does not provide exactly-once side effects or transaction rollback.
- Streaming resources still follow ADR-020: their ownership must survive while the accepted branch legitimately needs them. Lifetime does not imply stream replayability.

## Status of Implementation

Implemented and verified on branch `review/core-flow-errors`. The standard async operator is a
managed policy (`AsyncPolicy` with `asyncPolicyDeclaration`, registered by `MuseEngineRegistrar`);
accepted work is owned by an application-level supervisor and ledger (`BackgroundTaskManager`,
`BackgroundExecutionConfig`) rather than by the submitting request's coroutine scope; request
cancellation is delivered through `MuseEventEnvelope.requestCancellation` and cancels only that
request's job, never an accepted branch.

Named passing evidence:

- Engine temporal ITs (`AsyncPolicyLifecycleIT`, explicit session with independently controllable
  request/reply/background barriers): `explicit cancel before the reply leaves the request reply
  unchanged`, `explicit cancel after the reply releases the branch's claims`, `local recovery inside a
  branch stays local`, `an unhandled branch failure never re-enters the request handler`, `shutdown
  cancels unfinished work, joins finalizers and repeats idempotently`.
- Engine unit/IT (`BackgroundTaskManagerTest`, 24 tests): request-job cancellation after publication
  leaves the accepted branch running to normal finalization; capacity and running permits are released
  only after descendants and cleanup finish; deadline and rejection classification; shutdown grace
  then cancel/join.
- Engine source-contract ITs (`SourceCancellationLifecycleIT`): `a source cancellation signal cancels
  request execution and still releases its claims`, `stop keeps prepared policies alive until an
  accepted branch has terminated`.
- Packaged process and real-transport proof (`PackagedAsyncLifetimeE2ETest`): after the main reply the
  accepted branch is still blocked and can still read its retained non-repeatable input after release;
  when the client resets the connection while the reply write is in progress (the reply body itself
  never fails), the interrupted write closes exactly one reply lease while the branch keeps its own
  claim and reads the original bytes afterwards.

## References

- [ADR-020 — Async Streaming Lifetime Beyond the Main Response](ADR-020-async-streaming-lifetime.md)
- `muse-engine/src/main/kotlin/dev/museframework/engine/execution/policy/AsyncPolicy.kt`
- `muse-engine/src/main/kotlin/dev/museframework/engine/execution/policy/BackgroundTaskManager.kt`
- `muse-engine-api/src/main/kotlin/dev/museframework/engine/api/source/MuseEventEnvelope.kt`
