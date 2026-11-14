pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://central.sonatype.com/repository/maven-snapshots/") } // JavaSteam
    }
}

rootProject.name = "gamenative"
include(":app")
include(":ubuntufs")
include(":iq80-leveldb") // leveldb fork, upstream/ submodule; see iq80-leveldb/NOTICE.md
include(":snappy-java") // snappy-java fork, upstream/ submodule; see snappy-java/NOTICE.md
