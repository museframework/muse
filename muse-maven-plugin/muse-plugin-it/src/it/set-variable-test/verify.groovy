def outputDir = new File(basedir, "target/generated-sources/muse")
assert outputDir.exists() : "Output directory missing: $outputDir"

def configFiles = []
outputDir.eachFileRecurse { if (it.name.endsWith("Config.kt")) configFiles << it }
assert configFiles.size() == 1 : "Expected one generated set-variable config, got ${configFiles.size()}"

def generatedCode = configFiles[0].text
assert generatedCode.contains('setVariable("testVar")') : "String variable mapping missing"
assert generatedCode.contains('value = "Hello Variable"') : "String variable value missing"
assert generatedCode.contains('setVariable("counterVar")') : "Expression variable mapping missing"
assert generatedCode.contains('value = "#[10 +5]"') : "Expression variable value missing"
