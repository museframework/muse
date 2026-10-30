// Verify pom.xml.mulesoft backup exists
def backup = new File(basedir, "pom.xml.mulesoft")
assert backup.exists() : "pom.xml.mulesoft backup missing"
assert backup.text.contains("ORIGINAL-MARKER") : "Backup must contain original pom content"