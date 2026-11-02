def outputDir = new File(basedir, "target/generated-sources/muse")
def configs = []
outputDir.eachFileRecurse { if (it.name.endsWith("Config.kt")) configs << it }
assert configs.size() == 1 : "One generated configuration is required"
assert new File(basedir, "target/classes/dev/museframework/it/objectstore/generated/ObjectStoreGeneratedConfig.class").isFile() : "Generated Kotlin must compile in the isolated plugin realm"
