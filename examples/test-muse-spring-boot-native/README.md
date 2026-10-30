# Muse Native Image Test Module

This module tests GraalVM native image compilation for Muse Spring Boot applications.

## Prerequisites

### GraalVM Installation

Native image compilation requires GraalVM. Install it using one of these methods:

#### Option 1: SDKMAN (Recommended)
```bash
# Install SDKMAN if not already installed
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"

# Install GraalVM
sdk install java 22.3.r22-grl
sdk use java 22.3.r22-grl
```

#### Option 2: Direct Download
Download from [GraalVM website](https://www.graalvm.org/downloads/) and set JAVA_HOME:
```bash
export JAVA_HOME=/path/to/graalvm-jdk-21+
export PATH=$JAVA_HOME/bin:$PATH
```

#### Option 3: Homebrew (macOS)
```bash
brew install --cask graalvm/tap/graalvm-ce-java21
```

Verify installation:
```bash
java -version
# Should show: GraalVM version 22.3.0 (or similar)

native-image --version
# Should show: GraalVM version 22.3.0 (or similar)
```

## Building Native Image

### Build Command
```bash
cd test-spring-boot-native
mvn -Pnative package
```

### Expected Output
The build will:
1. Compile the application
2. Run Spring AOT processing
3. Generate native image using GraalVM
4. Create executable in `target/test-spring-boot-native`

### Running the Native Executable
```bash
./target/test-spring-boot-native
```

The application will start on port 8080 with a single endpoint:
- `GET /api/hello` - Returns "Hello from Muse Native!"

## Troubleshooting

### Error: "native-image is not installed"
**Cause:** JAVA_HOME points to regular JDK instead of GraalVM

**Solution:**
```bash
# Set JAVA_HOME to GraalVM installation
export JAVA_HOME=/path/to/graalvm-jdk-21+

# Verify
java -version | grep GraalVM
```

### Error: "Reflection configuration missing"
**Cause:** Classes using reflection need hints

**Solution:** This module uses `MuseConfigProvider` (no reflection) so this should not occur. If it does, add `reflect-config.json` in `src/main/resources/META-INF/native-image/dev.museframework/`.

### Error: "Resource configuration missing"
**Cause:** Resources need to be included in native image

**Solution:** Add `resource-config.json` in `src/main/resources/META-INF/native-image/dev.museframework/`.

## Architecture

### Reflection-Free Design
This module uses `MuseConfigProvider` instead of `MuseConfigDefinition`:

```kotlin
// ✅ Native-compatible (no reflection)
class NativeConfig : MuseConfigProvider {
    override fun flows(): List<MuseFlow> = listOf(helloFlow)
}

// ❌ Not native-compatible (uses reflection)
class OldConfig : MuseConfigDefinition() {
    val helloFlow = flow("hello-flow") { ... }
}
```

### Spring Native Support
- Spring Boot 4.0.2 includes native image support
- Spring AOT processing generates hints automatically
- Kotlin reflection is minimized

## Testing

### Regular JVM Test
```bash
mvn spring-boot:run
```

### Native Image Test
```bash
mvn -Pnative package
./target/test-spring-boot-native
```

### Verify Native
```bash
file target/test-spring-boot-native
# Should show: ELF 64-bit LSB executable, x86-64

ls -lh target/test-spring-boot-native
# Should show: ~50-100MB executable (not a JAR)
```

## Performance Comparison

| Metric | JVM | Native Image |
|--------|-----|--------------|
| Startup Time | ~2-3s | ~50-100ms |
| Memory Usage | ~300-500MB | ~50-100MB |
| Executable Size | ~20MB JAR | ~50-100MB binary |
| Throughput | Baseline | ~90-95% of JVM |

## Notes

- Native image build takes 5-15 minutes (one-time cost)
- First run may be slower due to JIT warmup
- Debugging native images requires different tools than JVM
- Not all JVM features are supported in native mode

## References

- [GraalVM Native Image Guide](https://www.graalvm.org/latest/reference-manual/native-image/)
- [Spring Native Reference](https://docs.spring.io/spring-native/docs/current/reference/htmlsingle/)
- [Kotlin GraalVM Guide](https://kotlinlang.org/docs/native-app-with-graalvm.html)