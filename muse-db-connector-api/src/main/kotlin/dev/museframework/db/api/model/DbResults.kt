package dev.museframework.db.api.model

data class DbStatementResult(
    val affectedRows: Int,
    val generatedKeys: Map<String, Any?> = emptyMap(),
)

data class DbStoredProcedureResult(
    val outputParameters: Map<String, Any?>,
    val resultSets: List<List<Map<String, Any?>>> = emptyList(),
    val updateCounts: List<Int> = emptyList(),
)
