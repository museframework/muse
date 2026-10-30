%dw 2.0
output application/json
---
{
    total: sum(payload.numbers),
    average: avg(payload.numbers),
    maximum: max(payload.numbers),
    minimum: min(payload.numbers),
    count: sizeOf(payload.numbers)
}
