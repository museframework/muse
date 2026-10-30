def kotlinDir = new File(basedir, "src/main/kotlin")
assert kotlinDir.exists() : "src/main/kotlin directory missing"

// Check that files were generated in the custom package directory
def packageDir = new File(kotlinDir, "com/custom/muse")
assert packageDir.exists() : "Package dir $packageDir missing"
assert packageDir.isDirectory() : "$packageDir is not a directory"

// Look for generated .kt files in the custom package
def ktFiles = []
packageDir.eachFile { if (it.name.endsWith(".kt")) ktFiles << it }
assert ktFiles.size() > 0 : "No .kt files in custom package $packageDir"

def configFile = ktFiles.find { it.name.endsWith("Config.kt") }
assert configFile != null : "No *Config.kt found in custom package"
assert configFile.text.contains("package com.custom.muse") : "Package declaration must be com.custom.muse"

def appFile = ktFiles.find { it.name.contains("Application") }
assert appFile != null : "No *Application.kt in custom package"
assert appFile.text.contains("com.custom.muse") : "Application must be in custom package"

// Verify pom references (migrate generates a new pom.xml)
def pomFile = new File(basedir, "pom.xml")
assert pomFile.exists() : "Generated pom.xml missing"