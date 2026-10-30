package dev.museframework.db.api.config

import dev.museframework.dsl.api.model.config.MuseConfiguration
import java.util.UUID

data class DbConfig(
    val name: String,
    val connection: DbConnectionConfig,
    val validateConnectivity: Boolean = true,
    override val docName: String = name,
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
) : MuseConfiguration {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(docName.isNotBlank()) { "docName must not be blank" }
    }
}

sealed interface DbConfigRef {
    data class ByName(
        val name: String,
    ) : DbConfigRef {
        init {
            require(name.isNotBlank()) { "Database configuration name must not be blank" }
        }
    }

    data class ByReference(
        val config: DbConfig,
    ) : DbConfigRef
}
