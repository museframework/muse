def outputDir = new File(basedir, "target/generated-sources/muse")
assert outputDir.exists() : "Output directory missing: $outputDir"

def configFiles = []
outputDir.eachFileRecurse { if (it.name.endsWith("Config.kt")) configFiles << it }
assert configFiles.size() == 1 : "Expected one generated APIKit config, got ${configFiles.size()}"

def generatedCode = configFiles[0].text
assert generatedCode.contains("apikitConfig") : "APIKit config emitter missing"
assert generatedCode.contains("apikit(") : "APIKit router DSL missing"
assert generatedCode.contains("transform") : "DataWeave transform DSL missing"
