%dw 2.0
output application/json
---
payload.items map ((item, index) -> {
    id: item.id,
    name: upper(item.name),
    position: index + 1
})
