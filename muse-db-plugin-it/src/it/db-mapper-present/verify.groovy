def outputDir = new File(basedir, "target/generated-sources/muse")
assert outputDir.exists() : "Output directory missing: $outputDir"

def configFiles = []
outputDir.eachFileRecurse { if (it.name.endsWith("Config.kt")) configFiles << it }
assert configFiles.size() == 1 : "Expected one generated DB config, got ${configFiles.size()}"

def generatedCode = configFiles[0].text
assert generatedCode.contains("import dev.museframework.db.api.dsl.dbConfig") : "DB config import missing"
assert generatedCode.contains("import dev.museframework.db.api.dsl.dbSelect") : "DB select import missing"
assert generatedCode.contains("import dev.museframework.db.api.config.DbConfigRef") : "DB config ref import missing"
assert generatedCode.contains("import dev.museframework.dsl.dsl.setPayload") : "Core provider import missing"
assert generatedCode.contains('dbConfig("Database_Config")') : "DB config DSL missing"
assert generatedCode.contains('url = "jdbc:h2:mem:db-plugin-realm"') : "DB JDBC URL missing"
assert generatedCode.contains('driverClassName = "org.h2.Driver"') : "DB driver class missing"
assert generatedCode.contains('dbSelect(DbConfigRef.ByName("Database_Config"))') : "DB select DSL missing"
assert generatedCode.contains('sql = "SELECT 1 AS value"') : "DB select SQL missing"
assert generatedCode.contains("setPayload") : "Core set-payload DSL missing"
assert !generatedCode.contains("dev.museframework.connectors.db") : "DB runtime package leaked into generated DSL"
