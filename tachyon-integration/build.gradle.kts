plugins {
    id("tachyon.java-conventions")
}

description = "Test-only probe for isolated Paper/Folia integration servers."

dependencies {
    compileOnly(project(":tachyon-platform-paper"))
    compileOnly(libs.paper.api)
}

tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("plugin.yml") { expand("version" to version) }
}

tasks.jar { archiveBaseName = "TachyonIntegrationProbe" }
