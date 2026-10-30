// Generated pom.xml
def pom = new File(basedir, "pom.xml")
assert pom.exists() : "Generated pom.xml missing"
assert pom.text.contains("muse-spring-boot") : "pom.xml must include muse-spring-boot dependency"

// Flow configs in src/main/kotlin/
def kotlinDir = new File(basedir, "src/main/kotlin")
assert kotlinDir.exists() : "src/main/kotlin directory missing"

def ktFiles = []
kotlinDir.eachFileRecurse { if (it.name.endsWith(".kt")) ktFiles << it }
assert ktFiles.size() > 0 : "No .kt files generated"

def configFile = ktFiles.find { it.name.endsWith("Config.kt") }
assert configFile != null : "No *Config.kt file found"
assert configFile.text.contains("@MuseConfig") : "@MuseConfig annotation missing"

// Entry point
def appFile = ktFiles.find { it.name.contains("Application") }
assert appFile != null : "No *Application.kt entry point found"
assert appFile.text.contains("runMuseApplication") : "Entry point must contain runMuseApplication"

// Ancillary files
def propsFile = new File(basedir, "src/main/resources/application-muse.properties")
assert propsFile.exists() : "application-muse.properties missing"
assert propsFile.text.contains("muse.application.name") : "Properties missing application name"

// .gitignore
def gitignore = new File(basedir, ".gitignore")
assert gitignore.exists() : ".gitignore missing"
assert gitignore.text.contains("target/") || gitignore.text.contains("build/") : ".gitignore missing entries"