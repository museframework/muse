# Database connector

The Database connector executes JDBC operations through a configured driver or `DataSource`. It supports SQL parameters, row streaming, a polling source, and typed operation builders.

## Maven dependencies

With `dev.museframework:muse-bom:0.1.0` imported in `dependencyManagement`, add:

```xml
<dependency>
    <groupId>dev.museframework</groupId>
    <artifactId>muse-db-connector-api</artifactId>
</dependency>
<dependency>
    <groupId>dev.museframework.connectors</groupId>
    <artifactId>muse-db-connector</artifactId>
</dependency>
```

Add a JDBC driver for your database as an application dependency. The connector does not bundle drivers for all vendors or register application drivers globally. The host must make the selected driver available in the configured classloader.

## Configuration and query

The example below assumes an application-provided H2 JDBC driver and an existing `account` table. The query matches a case covered by the connector's integration tests:

```kotlin
import dev.museframework.db.api.dsl.dbConfig
import dev.museframework.db.api.dsl.dbQuerySingle
import dev.museframework.dsl.dsl.flow

val database = dbConfig("h2") {
    generic {
        url = "jdbc:h2:mem:muse;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
        driverClassName = "org.h2.Driver"
        user = "sa"
        password = ""
    }
}

val account = flow("find-account") {
    dbQuerySingle(database) {
        sql = "SELECT name, balance FROM account WHERE id = :id"
        inputParameter("id", 1L)
    }
}
```

After the table contains account `id=1` with name `Ada` and balance `15`, the tested query contract returns `name=Ada` and `balance=15`. The table setup and host event source are intentionally omitted from this DSL excerpt. Do not expect the snippet alone to create the schema.

## Operations, verification, and limits

The DSL includes `dbSelect`, `dbQuerySingle`, `dbInsert`, `dbUpdate`, `dbDelete`, bulk insert/update/delete, DDL, SQL scripts, stored procedures and `dbListener` polling. `dbSelect` uses a cursor provider; select the streaming strategy supported by the runtime.

- [Database integration tests](../../muse-db-connector/src/test/kotlin/dev/museframework/connectors/db/execution/DbOperationsIT.kt) exercise H2 JDBC operations and parameterized queries.
- [Packaged Database E2E](../../muse-db-packaged-e2e/src/test/kotlin/dev/museframework/e2e/db/PackagedDatabaseConnectorE2ETest.kt) exercises a real packaged application and bundled test driver.
- [ADR-020](../adr/ADR-020-database-connector-and-driver-isolation.md) documents driver ownership and classloader isolation.

The tested H2 behavior is not evidence of compatibility with every database or driver. There is no claim of distributed transactions or automatic rollback across multiple connectors.
