plugins {
    id("tachyon.java-conventions")
    id("com.gradleup.shadow") version "9.6.1"
}

description = "The TachyonScript Paper plugin."

// Compile-time stand-ins for optional plugin APIs (PlaceholderAPI). They are never packaged:
// on a server the real classes are used, and only when the plugin is installed.
sourceSets {
    create("stubs")
}

dependencies {
    implementation(project(":tachyon-platform-paper"))
    compileOnly(libs.paper.api)
    compileOnly(sourceSets["stubs"].output)
    "stubsCompileOnly"(libs.paper.api)
    testImplementation(libs.paper.api)
}

tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("plugin.yml") {
        expand("version" to version)
    }
}

// Keep the established artifact name; HTTP libraries are isolated from other server plugins.
tasks.jar { enabled = false; dependsOn(tasks.shadowJar) }
tasks.shadowJar {
    archiveBaseName = "TachyonScript"
    archiveClassifier = ""
    relocate("okhttp3", "dev.tachyonscript.internal.okhttp3")
    relocate("okio", "dev.tachyonscript.internal.okio")
    relocate("kotlin", "dev.tachyonscript.internal.kotlin")
    relocate("org.jetbrains.annotations", "dev.tachyonscript.internal.annotations")
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    filesMatching(listOf("META-INF/*.kotlin_module", "META-INF/services/**")) { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
}
tasks.assemble { dependsOn(tasks.shadowJar) }
