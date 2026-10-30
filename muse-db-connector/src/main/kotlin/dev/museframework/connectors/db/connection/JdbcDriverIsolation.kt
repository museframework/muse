package dev.museframework.connectors.db.connection

import java.sql.Driver
import java.sql.DriverManager
import java.util.Collections
import java.util.IdentityHashMap

internal object JdbcDriverIsolation {
    @JvmStatic
    fun loadUnregistered(
        driverClassName: String,
        driverLoader: ClassLoader,
    ): Driver {
        // Snapshot before any child-realm work so a cold DriverManager SPI pass cannot
        // treat drivers discovered under an application TCCL as "pre-existing".
        val alreadyRegistered = Collections.newSetFromMap<Driver>(IdentityHashMap())
        alreadyRegistered.addAll(Collections.list(DriverManager.getDrivers()))

        try {
            val driverClass = Class.forName(driverClassName, true, driverLoader)
            if (!Driver::class.java.isAssignableFrom(driverClass)) {
                throw IllegalArgumentException(
                    "Configured class '$driverClassName' does not implement java.sql.Driver",
                )
            }
            return driverClass.getConstructor().newInstance() as Driver
        } finally {
            Collections.list(DriverManager.getDrivers()).forEach { registered ->
                if (registered !in alreadyRegistered) {
                    DriverManager.deregisterDriver(registered)
                }
            }
        }
    }
}
