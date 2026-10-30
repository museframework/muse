def classesDir = new File(basedir, "target/classes")
assert classesDir.exists() : "Classes directory missing: $classesDir"

def expectedClassPaths = [
    "dev/museframework/it/spring/HelloFlowConfig.class",
    "dev/museframework/it/spring/SpringBootCompileApplicationKt.class",
    "dev/museframework/it/spring/SpringBootCompileApplicationMarker.class",
]

expectedClassPaths.each { relativePath ->
    def classFile = new File(classesDir, relativePath)
    assert classFile.isFile() : "Expected compiled generated class missing: $relativePath"
    assert classFile.length() > 0 : "Compiled generated class is empty: $relativePath"
}

return true
