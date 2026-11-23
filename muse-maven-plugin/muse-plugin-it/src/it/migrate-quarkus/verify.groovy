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
def packageDir = new File(kotlinDir, "dev/museframework/it/quarkus")
def configFile = new File(packageDir, "SimpleAPIConfig.kt")
def helpers = new File(packageDir, "HelpersConfig.kt")
assert ktFiles.toSet() == [configFile, helpers].toSet()
assert configFile.text.contains('flowRef("finish-order")')
assert helpers.text.contains('subFlow("finish-order")')
assert configFile.text.contains("@ApplicationScoped") : "CDI scope missing"
assert configFile.text.contains("MuseConfigProvider") : "Native provider contract missing"
assert configFile.text.contains("override fun flows()") : "Explicit flow registration missing"
assert !configFile.text.contains("MuseConfigDefinition") : "Reflection-based config leaked into native output"
assert !configFile.text.contains("MuseConfigComponent") : "Spring stereotype leaked into Quarkus output"

def classesDir = new File(basedir, "target/classes/dev/museframework/it/quarkus")
assert new File(classesDir, "SimpleAPIConfig.class").isFile() : "Migrated Quarkus config did not compile"
assert new File(classesDir, "HelpersConfig.class").isFile() : "Migrated Quarkus helper config did not compile"

def properties = new File(basedir, "src/main/resources/application.properties")
assert properties.exists() : "Quarkus application.properties missing"
assert properties.text.contains("quarkus.application.name=MigrateQuarkus") : "Application name missing"
