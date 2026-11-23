def outputDir = new File(basedir, "target/generated-sources/muse")
assert outputDir.exists() : "Output directory missing: $outputDir"

def configFiles = []
outputDir.eachFileRecurse { if (it.name.endsWith("Config.kt")) configFiles << it }
assert configFiles.size() == 1 : "Expected one generated HTTP request config, got ${configFiles.size()}"

def generatedCode = configFiles[0].text
assert generatedCode.contains("httpRequestConnection") : "HTTP request connection missing"
assert generatedCode.contains('name = "http-request-config"') : "HTTP request connection name missing"
assert generatedCode.contains('host = "api.example.com"') : "HTTP request host missing"
assert generatedCode.contains('port = 443') : "HTTP request port missing"
assert generatedCode.contains('protocol = HttpProtocol.HTTPS') : "HTTP request protocol missing"
assert generatedCode.contains('import dev.museframework.http.api.dsl.httpRequest\n') : "HTTP request extension import missing"
assert generatedCode.contains('httpRequest(httpRequestConnection("http-request-config"))') : "HTTP request operation or reference missing"
assert generatedCode.contains('method = HttpMethod.POST') : "HTTP request method missing"
assert generatedCode.contains('targetConfig = TargetConfig("reply", "#[{payload: payload, attributes: attributes}]")') : "HTTP response target missing"
