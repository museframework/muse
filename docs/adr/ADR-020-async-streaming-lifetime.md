---
status: accepted
date: 2026-09-09
---

# ADR-020 — Async Streaming Lifetime Beyond the Main Response

A streaming value legitimately supplied to an async branch must remain usable by that branch after the main flow makes its response available. Main-response completion alone is not sufficient authority to release resources still needed by live async work. This preserves non-blocking response semantics without declaring async streaming unsupported.

## Consequences

- Distinguish main-response availability, async-branch completion, and streaming-resource release in the execution contract.
- Engine work and response adapters must coordinate resource ownership; returning a successful processor result is not a resource-lifetime signal.
- A1, the known early-release issue, is a required correction for this supported contract. It may be implemented as a separate task, but support cannot be claimed as verified while that issue remains.
- Resource lifetime does not imply replayability or concurrent readability. Non-repeatable providers retain their single-cursor constraint.
- Do not infer new coroutine parentage, cancellation-on-disconnect behavior, automatic payload materialization, or a specific lease implementation from this decision. Those mechanisms require a separate design decision.
- Cancellation and explicit shutdown remain legitimate ways to terminate a branch and its resource use; this ADR does not require unbounded execution.

## Status of Implementation

Implemented and verified on branch `review/core-flow-errors`. The requirement became a runtime
contract through the managed execution seam: reference-counted retained claims
(`StreamingResourceRegistry.retain` and `StreamingResourceLease`), the envelope resource protocol
(`MuseEventEnvelope.executionResources` and `replyResources`) and the exported response lease
(`HttpResponse.bodyLease`) replaced exclusive reply-resource transfer, and request-bound
non-repeatable input is promoted before background acceptance (`BackgroundStreamPreparation` on the
Spring adapter's `BackgroundRequestStreamProvider`). Main-response completion releases only the
claims that response owns.

Named passing evidence:

- Engine temporal IT: `AsyncPolicyLifecycleIT.stream input stays readable after the reply while the branch still runs`.
- Engine source-contract IT: `SourceCancellationLifecycleIT.the root reply does not close a stream an accepted async branch still reads`.
- API registry unit: `StreamingResourceLeaseTest.closing response claim cannot close an accepted branch provider` and `StreamingResourceLeaseTest.every root response branch completion order cleans exactly once at the last owner`.
- HTTP adapter ITs: `SpringHttpServerIT.streaming response cleanup goes through the retained lease`, `SpringHttpServerIT.a response setup failure closes exactly the lease the response owns`, and their Quarkus counterparts in `QuarkusHttpServerBehaviorIT`.
- Packaged process and real-transport proof (`PackagedAsyncLifetimeE2ETest`, packaged JVM, `non-repeatable` strategy):
  - `packaged main reply returns before the accepted branch reads its retained non-repeatable input`: the main reply is observed while the accepted branch is still blocked, the request body is already promoted into the file store before release, the branch then reads the original bytes, and the spool file is gone once the branch finalizes.
  - `packaged transport failure while the reply is written closes the reply lease and leaves the accepted branch readable`: the client resets the connection (`SO_LINGER(0)`) while the adapter is poised inside the response write (the gated reply body has served zero bytes at that point), the reply body never fails on its own, and the interrupted write still closes exactly one lease (one `close`, one `releaseResources`) with the live branch's retained input untouched and readable afterwards.
  - `packaged response serialization failure closes the reply lease and leaves the accepted branch readable` covers the deterministic fixture-side serialization failure as a separate proof.

A1 — the early-release issue this ADR names as a required correction — is closed on this evidence.

## References

- [ADR-018 — Streaming Payloads and Resource Ownership](ADR-018-repeatable-streaming.md)
- [ADR-021 — Async Work Survives Originating Request Cancellation](ADR-021-async-request-cancellation.md) records the subsequent, separate cancellation decision.
