# Muse Integration Flows

Muse describes integration work as flows and reusable fragments operating on events. The language below distinguishes the integration definition, its individual executions, and the response observed by its caller.

## Language

**Flow**:
A named integration definition that may have an event source and an error handler of its own.
_Avoid_: Pipeline, sub-flow, invocation

**Sub-flow**:
A named reusable integration fragment without a source or an error handler of its own.
_Avoid_: Flow, independent error scope

**Inline body**:
An unnamed integration fragment contained in a router or scope definition, including one defined by an extension.
_Avoid_: Named flow, generated helper flow

**Flow invocation**:
An individual execution of a flow for a particular input event, distinct from the reusable flow definition.
_Avoid_: Flow definition, response

**Main response**:
The result made available to the originator of a flow invocation, distinct from the remaining work of its asynchronous branches.
_Avoid_: Completion of all flow work, release of all flow resources

**Async branch**:
A flow branch whose result is not merged into the main response and whose execution may outlive that response. Once accepted, it continues independently of cancellation of its originating request.
_Avoid_: Detached job, completed invocation

**Policy-defined success**:
A successful completion of an integration operation under its explicitly declared success criterion, including a valid replacement result when all attempted bodies fail. It is distinct from recovery performed by a declared Flow or Try handler.
_Avoid_: Handler recovery, success of every attempt
