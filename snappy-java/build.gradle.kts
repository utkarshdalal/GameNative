plugins {
    id("java-library")
}

// java half of the snappy-java fork (upstream/ submodule, branch gamenative @ v1.1.10.8).
// native half is built by the app's externalNativeBuild against upstream/android/CMakeLists.txt --
// the jar's prebuilt .so is 4KB-aligned and Play needs 16KB. see NOTICE.md.

val upstream = layout.projectDirectory.dir("upstream")

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

sourceSets {
    named("main") {
        java {
            setSrcDirs(listOf(upstream.dir("src/main/java")))
            // OSGi lifecycle hook; org.osgi.framework never exists on Android
            exclude("org/xerial/snappy/SnappyBundleActivator.java")
        }
        resources {
            setSrcDirs(listOf(upstream.dir("src/main/resources")))
            // android ones are what we replace; desktop ones are 2.6MB that can't run on Android.
            // republished below for host tests.
            exclude("org/xerial/snappy/native/**")
        }
    }
    named("test") {
        java.setSrcDirs(emptyList<String>())
        resources.setSrcDirs(emptyList<String>())
    }
}

// robolectric runs on the host JVM, where PluviaApp skips use.systemlib and snappy resolves its
// native from classpath resources. a jar, not a raw dir -- AGP's unit-test classpath ignores dirs.
val desktopNatives by tasks.registering(Jar::class) {
    archiveClassifier.set("desktop-natives")
    from(upstream.dir("src/main/resources")) {
        include("org/xerial/snappy/native/**")
        exclude("org/xerial/snappy/native/Linux/android-*/**")
    }
}

configurations.create("desktopNatives") {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add("desktopNatives", desktopNatives)
}

gradle.taskGraph.whenReady {
    if (!upstream.file("src/main/java/org/xerial/snappy/Snappy.java").asFile.exists()) {
        throw GradleException("snappy-java/upstream is empty — git submodule update --init --recursive")
    }
}
