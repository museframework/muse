package dev.museframework.connectors.db

import dev.museframework.connectors.db.connection.DbConnectionFactory
import dev.museframework.db.api.config.DbConfig
import dev.museframework.db.api.config.GenericDbConnectionConfig
import dev.museframework.dsl.api.model.config.GlobalProperty
import dev.museframework.engine.app.MuseApplicationConfig
import dev.museframework.engine.app.bootstrap
import dev.museframework.engine.factory.MuseComponentFactory
import dev.museframework.engine.factory.MuseEngineRegistrar
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DbConnectivityPropertyOrderIT {
    @Test
    fun `database config declared before a global property validates the resolved url`() {
        val factory = MuseComponentFactory()
        MuseEngineRegistrar().register(factory)
        DbConnectorRegistrar().register(factory)
        val database =
            DbConfig(
                name = "orders",
                connection =
                    GenericDbConnectionConfig(
                        url = "jdbc:h2:${'$'}{db.name}",
                        driverClassName = "org.h2.Driver",
                        user = "sa",
                        password = "",
                    ),
            )
        val property =
            GlobalProperty(
                docName = "db-name",
                name = "db.name",
                value = "mem:propertyorder;DB_CLOSE_DELAY=-1",
            )

        val application =
            bootstrap(
                MuseApplicationConfig(
                    flows = emptyList(),
                    configs = listOf(database, property),
                    factory = factory,
                ),
            )

        val connection = DbConnectionFactory(database, application.context.properties).open()
        connection.use {
            assertFalse(it.isClosed)
            val url = it.metaData.url
            assertTrue(url.contains("jdbc:h2:mem:propertyorder"), url)
            assertFalse(url.contains("${'$'}{db.name}"), url)
        }
    }
}
