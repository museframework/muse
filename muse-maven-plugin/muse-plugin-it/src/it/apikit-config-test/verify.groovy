def outputDir = new File(basedir, "target/generated-sources/muse")
assert outputDir.exists() : "Output directory missing: $outputDir"

def configFiles = []
outputDir.eachFileRecurse { if (it.name.endsWith("Config.kt")) configFiles << it }
assert configFiles.size() == 1 : "Expected one generated APIKit config, got ${configFiles.size()}"

def generatedCode = configFiles[0].text
assert generatedCode.contains("import dev.museframework.apikit.api.dsl.apikitConfig") : "APIKit config import missing"
assert generatedCode.contains('name = "api-config"') : "APIKit config name missing"
assert generatedCode.contains('spec = "api/api-spec.raml"') : "APIKit specification path missing"
assert generatedCode.contains('apikit("api-config")') : "APIKit router must bind to the original config name"
assert generatedCode.contains("transform") : "DataWeave transform DSL missing"
