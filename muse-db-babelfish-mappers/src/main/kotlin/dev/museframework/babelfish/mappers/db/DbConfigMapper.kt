package dev.museframework.babelfish.mappers.db

import dev.museframework.babelfish.api.ConfigMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ConfigCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawConfig
import dev.museframework.db.api.config.DataSourceDbConnectionConfig
import dev.museframework.db.api.config.DbConfig
import dev.museframework.db.api.config.DbTransactionIsolation
import dev.museframework.db.api.config.GenericDbConnectionConfig
import java.util.UUID

object DbConfigMapper : ConfigMapper {
    override fun map(
        config: RawConfig,
        ctx: MappingContext,
    ): DbConfig {
        val name = config.attrs["name"] ?: config.id
        val connectionElement =
            config.children.firstOrNull { it.tag == "generic-connection" || it.tag == "generic" }
                ?: config.children.firstOrNull { it.tag == "data-source-connection" }
                    ?.let {
                        throw IllegalArgumentException(
                            "db:data-source-connection requires an application-owned DataSource and cannot be " +
                                "generated from XML; use db:generic-connection",
                        )
                    }
                ?: throw IllegalArgumentException("db:config '$name' requires a generic connection")
        val properties =
            connectionElement.descendants()
                .filter { it.tag == "property" || it.tag == "connection-property" }
                .associate { property ->
                    val key = property.attrs["key"] ?: property.attrs["name"]
                        ?: throw IllegalArgumentException("Database connection property requires key")
                    val value = property.attrs["value"] ?: property.textContent?.trim().orEmpty()
                    key to value
                }
        val isolation =
            connectionElement.attrs["transactionIsolation"]
                ?.uppercase()
                ?.let { if (it == "NOT_CONFIGURED") DbTransactionIsolation.DEFAULT else DbTransactionIsolation.valueOf(it) }
                ?: DbTransactionIsolation.DEFAULT
        val reconnection = config.children.firstOrNull { it.tag == "reconnection" }
        val validateConnectivity =
            config.attrs["validateConnectivity"]?.toBooleanStrictOrNull()
                ?: reconnection?.attrs?.get("failsDeployment")?.toBooleanStrictOrNull()
                ?: false

        return DbConfig(
            name = name,
            connection =
                GenericDbConnectionConfig(
                    url = connectionElement.requireAttr("url", connectionElement.location),
                    driverClassName = connectionElement.requireAttr("driverClassName", connectionElement.location),
                    user = connectionElement.attrs["user"],
                    password = connectionElement.attrs["password"],
                    connectionProperties = properties,
                    transactionIsolation = isolation,
                ),
            validateConnectivity = validateConnectivity,
            docName = config.attrs["doc:name"] ?: name,
            docId = config.attrs["doc:id"] ?: UUID.randomUUID().toString(),
            sourceFile = ctx.sourceFile.toString(),
            sourceLine = config.location.line,
        )
    }
}

object DbConfigCodeEmitter : ConfigCodeEmitter<DbConfig> {
    override fun emit(
        config: DbConfig,
        ctx: CodegenContext,
    ): String {
        val connection = config.connection
        if (connection is DataSourceDbConnectionConfig) {
            throw IllegalArgumentException("A DataSource database configuration cannot be emitted without an application DataSource")
        }
        connection as GenericDbConnectionConfig
        ctx.addImport("dev.museframework.db.api.dsl.dbConfig")
        if (connection.transactionIsolation != DbTransactionIsolation.DEFAULT) {
            ctx.addImport("dev.museframework.db.api.config.DbTransactionIsolation")
        }

        return buildString {
            appendLine("dbConfig(${config.name.kotlinLiteral()}) {")
            if (config.docName != config.name) appendLine("    docName = ${config.docName.kotlinLiteral()}")
            if (!config.validateConnectivity) appendLine("    validateConnectivity = false")
            appendLine("    generic {")
            appendLine("        url = ${connection.url.kotlinLiteral()}")
            appendLine("        driverClassName = ${connection.driverClassName.kotlinLiteral()}")
            connection.user?.let { appendLine("        user = ${it.kotlinLiteral()}") }
            connection.password?.let { appendLine("        password = ${it.kotlinLiteral()}") }
            connection.connectionProperties.forEach { (name, value) ->
                appendLine("        connectionProperty(${name.kotlinLiteral()}, ${value.kotlinLiteral()})")
            }
            if (connection.transactionIsolation != DbTransactionIsolation.DEFAULT) {
                appendLine("        transactionIsolation = DbTransactionIsolation.${connection.transactionIsolation}")
            }
            appendLine("    }")
            append("}")
        }
    }
}
