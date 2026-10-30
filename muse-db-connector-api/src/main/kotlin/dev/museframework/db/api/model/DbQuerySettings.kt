package dev.museframework.db.api.model

import java.time.Duration

data class DbQuerySettings(
    val queryTimeout: Duration = Duration.ZERO,
    val fetchSize: Int? = null,
    val maxRows: Int? = null,
    val useColumnNumbers: Boolean = false,
) {
    init {
        require(!queryTimeout.isNegative) { "queryTimeout must not be negative" }
        require(fetchSize == null || fetchSize >= 0) { "fetchSize must be greater than or equal to zero" }
        require(maxRows == null || maxRows >= 0) { "maxRows must be greater than or equal to zero" }
    }

    fun timeoutSeconds(): Int {
        if (queryTimeout.isZero) return 0
        val seconds = queryTimeout.seconds + if (queryTimeout.nano == 0) 0 else 1
        require(seconds <= Int.MAX_VALUE) { "queryTimeout exceeds the JDBC integer-seconds limit" }
        return seconds.toInt()
    }
}
