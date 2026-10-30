def pom = new File(basedir, "pom.xml")
assert pom.exists() : "Generated pom.xml missing"
assert pom.text.contains("quarkus-maven-plugin") : "Quarkus Maven plugin missing"
assert pom.text.contains("muse-quarkus") : "Muse Quarkus extension missing"
assert pom.text.contains("quarkus-container-image-jib") : "Container image extension missing"
assert pom.text.contains("<quarkus.native.enabled>true</quarkus.native.enabled>") : "Native profile missing"

assert new File(basedir, "pom.xml.mulesoft").exists() : "Original POM backup missing"

def kotlinDir = new File(basedir, "src/main/kotlin")
def ktFiles = []
kotlinDir.eachFileRecurse { if (it.name.endsWith(".kt")) ktFiles << it }
assert ktFiles.size() == 1 : "Quarkus migration should generate one config and no synthetic entry point"
def configFile = ktFiles[0]
assert configFile.text.contains("@ApplicationScoped") : "CDI scope missing"
assert configFile.text.contains("MuseConfigProvider") : "Native provider contract missing"
assert configFile.text.contains("override fun flows()") : "Explicit flow registration missing"
assert !configFile.text.contains("MuseConfigDefinition") : "Reflection-based config leaked into native output"
assert !configFile.text.contains("MuseConfigComponent") : "Spring stereotype leaked into Quarkus output"

def properties = new File(basedir, "src/main/resources/application.properties")
assert properties.exists() : "Quarkus application.properties missing"
assert properties.text.contains("quarkus.application.name=MigrateQuarkus") : "Application name missing"
