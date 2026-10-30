// Generated pom.xml
def pom = new File(basedir, "pom.xml")
assert pom.exists() : "Generated pom.xml missing"
assert pom.text.contains("muse-spring-boot") : "pom.xml must include muse-spring-boot dependency"

// Flow configs in src/main/kotlin/
def kotlinDir = new File(basedir, "src/main/kotlin")
assert kotlinDir.exists() : "src/main/kotlin directory missing"

def ktFiles = []
kotlinDir.eachFileRecurse { if (it.name.endsWith(".kt")) ktFiles << it }
assert ktFiles.size() >= 2 : "Expected at least 2 .kt files, got ${ktFiles.size()}"

// Config file with @MuseConfig
def configFile = ktFiles.find { it.name.endsWith("Config.kt") }
assert configFile != null : "No *Config.kt file found"
assert configFile.text.contains("@MuseConfig") : "@MuseConfig annotation missing"

// Entry point
def appFile = ktFiles.find { it.name.contains("Application") }
assert appFile != null : "No *Application.kt entry point found"
assert appFile.text.contains("runMuseApplication") : "Entry point must contain runMuseApplication"

// Verify sub-flow names are preserved in generated code
def allKotlinCode = ktFiles.collect { it.text }.join("\n")
assert allKotlinCode.contains("order-processing-impl") : "Main sub-flow name not found"
assert allKotlinCode.contains("order-processing-update-target-a") : "Update target A sub-flow not found"
assert allKotlinCode.contains("order-processing-update-target-b") : "Update target B sub-flow not found"

// Verify flowRef references are preserved
assert allKotlinCode.contains("flowRef") : "flowRef not found in generated code"

// Verify try and error handler constructs
assert allKotlinCode.contains("try") : "try block not found in generated code"

// Verify choice router
assert allKotlinCode.contains("choice") : "choice router not found in generated code"

// Ancillary files
def propsFile = new File(basedir, "src/main/resources/application-muse.properties")
assert propsFile.exists() : "application-muse.properties missing"

// .gitignore
def gitignore = new File(basedir, ".gitignore")
assert gitignore.exists() : ".gitignore missing"

// Backup
def backup = new File(basedir, "pom.xml.mulesoft")
assert backup.exists() : "pom.xml.mulesoft backup missing"
