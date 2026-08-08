plugins {
    id("java-library")
}

// Vendored fork of iq80 leveldb 0.12 (pure-Java LevelDB). See NOTICE.md for origin + license.
// Only patch vs upstream: Table.uncompressedScratch is now a per-thread ThreadLocal instead of a
// shared static ByteBuffer, fixing concurrent-decompression block corruption (a reader thread and
// iq80's background-compaction thread decompressed snappy blocks into the same buffer under
// different class-monitor locks). Vendored as source (not a prebuilt jar) so it builds reproducibly
// from source — matches the F-Droid distribution posture.

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

// compile against the Java 8 API surface (--release 8), NOT merely -source/-target 8. the JDK-17
// ByteBuffer/MappedByteBuffer covariant overrides (duplicate()/position()/clear() returning the
// subtype, not Buffer) only exist on Android 14+ (API 34). compiling at release 8 binds these calls
// to the Java-8 Buffer signatures present on ALL Android levels -- matching the original published
// iq80 0.12 jar. without this, leveldb mmap reads NoSuchMethodError on API<34 (repro: Android 13 /
// WebView 109). lib-scoped on purpose: no app-global coreLibraryDesugaring, no per-call-site patch.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

dependencies {
    // compile-time only — these mirror iq80 0.12's own dependencies so the vendored source compiles
    // to bytecode identical to the published jar. at RUNTIME the GameNative app supplies guava +
    // snappy-java on the shared classpath; org.iq80.snappy is never loaded (the SPI selects xerial).
    // guava is pinned to 19.0 ONLY for this module's compile because iq80 calls Throwables.propagate
    // (removed in guava 20+); the app's resolved guava (33.x) governs at runtime, exactly as it did
    // when this was the published maven artifact.
    // hardcoded coords (not the app version catalog) so this vendored module is self-contained —
    // it must configure independently of when the app adds these to libs.versions.toml.
    compileOnly("com.google.guava:guava:19.0")
    compileOnly("org.xerial.snappy:snappy-java:1.1.10.8")
    compileOnly("org.iq80.snappy:snappy:0.4")
}
