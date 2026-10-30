def outputDir = new File(basedir, "target/generated-sources/muse")
assert outputDir.exists() : "Output directory missing: $outputDir"

def configFiles = []
outputDir.eachFileRecurse { if (it.name.endsWith("Config.kt")) configFiles << it }
assert configFiles.size() == 1 : "Expected one generated HTTP listener config, got ${configFiles.size()}"

def generatedCode = configFiles[0].text
assert generatedCode.contains('httpListenerConnection("http-listener-config")') : "HTTP listener connection missing"
assert generatedCode.contains('host = "0.0.0.0"') : "HTTP listener host missing"
assert generatedCode.contains('port = 8081') : "HTTP listener port missing"
assert generatedCode.contains('httpListener("http-listener-config")') : "HTTP listener source missing"
assert generatedCode.contains('path = "/test"') : "HTTP listener path missing"
