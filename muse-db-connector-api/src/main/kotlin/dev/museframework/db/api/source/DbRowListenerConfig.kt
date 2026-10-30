package dev.museframework.db.api.source

import dev.museframework.db.api.config.DbConfigRef
import dev.museframework.db.api.model.DbQuerySettings
import dev.museframework.dsl.api.config.FixedFrequencyStrategy
import dev.museframework.dsl.api.model.component.Source
import java.util.UUID

data class DbRowListenerConfig(
    val configRef: DbConfigRef,
    val table: String,
    val watermarkColumn: String? = null,
    val idColumn: String? = null,
    val settings: DbQuerySettings = DbQuerySettings(),
    val schedulingStrategy: FixedFrequencyStrategy = FixedFrequencyStrategy(),
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
) : Source {
    init {
        require(table.isNotBlank()) { "table must not be blank" }
        require(watermarkColumn == null || watermarkColumn.isNotBlank()) { "watermarkColumn must not be blank" }
        require(idColumn == null || idColumn.isNotBlank()) { "idColumn must not be blank" }
    }
}
