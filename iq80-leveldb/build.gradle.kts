plugins {
    id("java-library")
}

// build wrapper for the iq80 leveldb 0.12 fork in the `upstream/` submodule (see NOTICE.md for patches).
// compiles upstream's separate leveldb-api/ and leveldb/ maven modules into one library.

val upstream = layout.projectDirectory.dir("upstream")

sourceSets {
    named("main") {
        java.setSrcDirs(
            listOf(
                upstream.dir("leveldb-api/src/main/java"),
                upstream.dir("leveldb/src/main/java"),
            ),
        )
        resources.setSrcDirs(listOf(upstream.dir("leveldb/src/main/resources")))
    }
    named("test") {
        java.setSrcDirs(emptyList<String>())
        resources.setSrcDirs(emptyList<String>())
    }
}

// fail loudly rather than silently producing an empty jar when the submodule has not been
// initialised (fresh clone without --recurse-submodules).
gradle.taskGraph.whenReady {
    if (!upstream.file("leveldb/src/main/java/org/iq80/leveldb/impl/DbImpl.java").asFile.exists()) {
        throw GradleException(
            "iq80-leveldb/upstream is empty — run: git submodule update --init --recursive",
        )
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

// --release 8, NOT merely -source/-target 8: the JDK-17 ByteBuffer covariant overrides (duplicate()/
// position()/clear() returning the subtype) only exist on API 34+, so without this leveldb mmap reads
// throw NoSuchMethodError on older Android. release 8 binds the Buffer signatures present everywhere.
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
