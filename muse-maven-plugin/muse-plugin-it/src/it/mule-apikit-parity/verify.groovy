import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit

def buildLog = new File(basedir, "build.log").text
assert buildLog.contains("All components are implemented in Muse") :
    "muse:check must accept parent-owned HTTP and DataWeave XML children"
assert new File(basedir, "target/generated-sources/muse/dev/museframework/examples/InterfaceConfig.kt").isFile() :
    "Expected generated APIKit flows"
assert new File(basedir, "target/classes/dev/museframework/examples/InterfaceConfig.class").isFile() :
    "Expected compiled generated flow"

def localRepo = new File(basedir, "../../local-repo").canonicalPath
def runLog = new File(basedir, "target/invoker-muse-run.log")
def process = new ProcessBuilder(
    "mvn", "--batch-mode", "-Dkotlin.daemon.enabled=false",
    "-Dmaven.repo.local=" + localRepo, "muse:run",
).directory(basedir).redirectErrorStream(true).redirectOutput(runLog).start()

def request = { String method, String path, String mediaType = null, String body = null ->
    HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:8080" + path).openConnection()
    c.connectTimeout = 1500
    c.readTimeout = 3000
    c.requestMethod = method
    if (mediaType != null) c.setRequestProperty("Content-Type", mediaType)
    if (body != null) {
        c.doOutput = true
        c.outputStream.withCloseable { it.write(body.getBytes("UTF-8")) }
    }
    try {
        int status = c.responseCode
        def stream = status >= 400 ? c.errorStream : c.inputStream
        def text = stream == null ? "" : stream.withCloseable { it.getText("UTF-8") }
        [status: status, body: text, contentType: c.getHeaderField("Content-Type")]
    } finally {
        c.disconnect()
    }
}

try {
    boolean ready = false
    long deadline = System.currentTimeMillis() + 120_000L
    while (System.currentTimeMillis() < deadline) {
        if (!process.isAlive()) break
        try {
            def result = request("GET", "/api/resources")
            if (result.status == 200 && result.body.contains("execution_time")) {
                ready = true
                break
            }
        } catch (IOException ignored) {
            // The server is still starting.
        }
        Thread.sleep(1000)
    }

    assert ready : "muse:run did not start with HTTP/APIKit providers; log:\n" + runLog.text.takeRight(6000)

    def binary = request("POST", "/api/resources", "application/octet-stream", "hello-binary")
    assert binary.status == 200 && binary.body == "hello-binary" : "binary echo: " + binary

    def notFound = request("GET", "/api/missing")
    assert notFound.status == 404 : "expected 404: " + notFound
    assert notFound.contentType?.startsWith("application/json") : "expected JSON not found: " + notFound
    assert notFound.body.contains("Resource not found") : "wrong error body: " + notFound

    def unsupported = request("POST", "/api/resources", "application/json", '{"a":1}')
    assert unsupported.status == 415 : "expected 415: " + unsupported
    assert unsupported.contentType?.startsWith("application/json") : "expected JSON media error: " + unsupported
    assert unsupported.body.contains("Unsupported media type") : "wrong media error body: " + unsupported

    def method = request("PUT", "/api/resources", "application/octet-stream", "x")
    assert method.status == 405 : "expected 405: " + method
    assert method.contentType?.startsWith("application/json") : "expected JSON method error: " + method
    assert method.body.contains("Method not allowed") : "wrong method error body: " + method

    return true
} finally {
    process.toHandle().descendants().forEach { it.destroy() }
    process.destroy()
    if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
}
