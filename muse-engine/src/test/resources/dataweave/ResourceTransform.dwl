%dw 2.0
import decorate from dataweave::ResourceHelpers
output application/java
---
{value: decorate(payload.value), suffix: vars.suffix, correlation: correlationId}
