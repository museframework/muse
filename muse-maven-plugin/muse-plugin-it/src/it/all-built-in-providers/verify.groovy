def outputDir = new File(basedir, "target/generated-sources/muse")
assert outputDir.exists() : "Output directory missing: $outputDir"

def configFiles = []
outputDir.eachFileRecurse { if (it.name.endsWith("Config.kt")) configFiles << it }
assert configFiles.size() == 1 : "Expected one generated all-provider config, got ${configFiles.size()}"

def generatedCode = configFiles[0].text
assert generatedCode.contains("import dev.museframework.dsl.dsl.setPayload") : "Core provider import missing"
assert generatedCode.contains("import dev.museframework.http.api.dsl.httpListener") : "HTTP provider import missing"
assert generatedCode.contains("import dev.museframework.apikit.api.dsl.apikit") : "APIKit provider import missing"
assert generatedCode.contains("import dev.museframework.db.api.dsl.dbConfig") : "Database config provider import missing"
assert generatedCode.contains("import dev.museframework.db.api.dsl.dbSelect") : "Database operation provider import missing"
assert generatedCode.contains('httpListener("http-listener-config")') : "HTTP listener DSL missing"
assert generatedCode.contains('apikit("api-config")') : "APIKit router DSL missing"
assert generatedCode.contains('dbSelect(DbConfigRef.ByName("database-config"))') : "Database select DSL missing"
assert !generatedCode.contains("dev.museframework.connectors.db") : "Database runtime leaked into generated source"
assert generatedCode.contains("setPayload") : "Core set-payload DSL missing"
assert generatedCode.contains("import dev.museframework.dsl.dsl.forEach") : "Core foreach DSL import missing"
assert generatedCode.contains("import dev.museframework.dsl.dsl.parallelForEach") : "Core parallel foreach DSL import missing"
assert generatedCode.contains("import dev.museframework.dsl.dsl.scatterGather") : "Core scatter gather DSL import missing"
assert generatedCode.contains("forEach {") : "Core foreach DSL missing"
assert generatedCode.contains("batchSize = 2") : "Core foreach batch size missing"
assert generatedCode.contains('rootMessageVariableName = "root"') : "Core foreach root variable missing"
assert generatedCode.contains('counterVariableName = "iteration"') : "Core foreach counter variable missing"
assert generatedCode.contains("parallelForEach {") : "Core parallel foreach DSL missing"
assert generatedCode.contains('target = "parallelCount"') : "Parallel foreach target missing"
assert generatedCode.contains('targetValue = "#[sizeOf(payload)]"') : "Parallel foreach target value missing"
assert generatedCode.contains("scatterGather {") : "Core scatter gather DSL missing"
assert generatedCode.contains('target = "scatterCount"') : "Scatter gather target missing"
