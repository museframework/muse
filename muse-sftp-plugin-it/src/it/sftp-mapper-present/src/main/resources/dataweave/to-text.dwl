%dw 2.0
output text/plain
---
payload map ((row) -> row.id) joinBy "\n"
