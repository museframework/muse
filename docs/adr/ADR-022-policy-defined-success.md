---
status: accepted
date: 2026-09-09
---

# ADR-022 — Policy-Defined Success May Use a Replacement Result

An explicitly contracted execution policy may complete successfully with a valid replacement result even when all attempted bodies fail. The success criterion belongs to the composed operation; success does not require a successful child invocation or execution of a DSL onErrorContinue handler. This is the user's accepted Q3.1 decision and preserves extension-defined fallback and partial-result algorithms rather than limiting every operation to core-defined success criteria.

## Consequences

- Policies must document their success criterion, eligible modeled errors, replacement-result validity, and event-mutation behavior. The engine cannot prove arbitrary business correctness from a successful child result or a terminal result constructor.
- Policy-defined success is distinct from handler recovery. The engine retains declared-handler ownership and semantics; a policy does not gain authority to invoke a caller's handler or bypass an inner scope's handler.
- Standard foreach and scatter-gather contracts do not become best-effort or quorum operations. Different success criteria require a distinct explicit policy or configuration contract.
- Cancellation and execution-contract defects are not ordinary modeled failures that a policy may silently recover into success. Legitimate child-local recovery remains complete before its result reaches the outer policy.
- No recovery flag or error-owner metadata is required in terminal ProcessorResult merely to express this decision.
- Intermediate failure observability (Q3.2) is deliberately deferred to the backlog at the user's request. It is not a prerequisite for this accepted capability; existing logs and terminal error contracts remain unchanged. No diagnostic dispatcher, durable audit, telemetry backend, public observer interface, or payload/error-graph capture is approved.

## Status of Implementation

This records the accepted capability, not an implemented public policy interface. Concrete signatures, binding/registration, and an updated implementation plan still require review. Optional diagnostics is tracked separately and does not block that work. No runtime changes or Maven runs accompanied this ADR.
