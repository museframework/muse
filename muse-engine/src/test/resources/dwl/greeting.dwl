%dw 2.0
output application/json
---
{
    greeting: "Hello from resource!",
    processed: payload.name ++ " - processed"
}
