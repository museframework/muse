def log = new File(basedir, "build.log").text.replace('\\', '/')
def input = new File(basedir, "src/main/mule").canonicalPath.replace('\\', '/')
def message = "Both '${input}/first/order-API.XML' and '${input}/second/order_API.xml' generate 'org/example/collision/OrderAPIConfig.kt'"
assert log.count(message) == 2 : "Both goals must report the same ordered source paths"
assert !new File(basedir, "target/generated-sources/muse").exists()
assert !new File(basedir, "src/main/kotlin").exists()
assert !new File(basedir, "pom.xml.mulesoft").exists()
assert new File(basedir, "pom.xml").text.contains("<artifactId>muse-maven-plugin</artifactId>")
