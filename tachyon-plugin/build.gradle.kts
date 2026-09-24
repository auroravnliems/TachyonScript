plugins {
    id("tachyon.java-conventions")
}

description = "The TachyonScript Paper plugin."

dependencies {
    implementation(project(":tachyon-platform-paper"))
    compileOnly(libs.paper.api)
    testImplementation(libs.paper.api)
}

tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("plugin.yml") {
        expand("version" to version)
    }
}

// The plugin jar bundles the TachyonScript modules. Paper provides Bukkit and Adventure at
// runtime, and there are no other runtime dependencies, so no shading/relocation is needed.
tasks.jar {
    archiveBaseName = "TachyonScript"
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) }
    }) {
        exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
