rootProject.name = "tachyonscript"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
            content {
                includeGroup("io.papermc.paper")
                includeGroup("com.mojang")
                includeGroup("net.md-5")
            }
        }
    }
}

include(
    "tachyon-api",
    "tachyon-language",
    "tachyon-ir",
    "tachyon-compiler",
    "tachyon-runtime",
    "tachyon-engine",
    "tachyon-stdlib",
    "tachyon-platform-paper",
    "tachyon-plugin",
    "tachyon-cli",
    "tachyon-tests",
    "tachyon-benchmarks",
)
