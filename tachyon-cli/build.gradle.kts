plugins {
    id("tachyon.java-conventions")
    application
}

description = "Command-line tools: check scripts and dump compiler stages without a server."

dependencies {
    implementation(project(":tachyon-compiler"))
    implementation(project(":tachyon-runtime"))
    implementation(project(":tachyon-stdlib"))
}

application {
    mainClass = "dev.tachyonscript.cli.Cli"
    applicationName = "tys"
}

// The tests compile every example in the documentation and check that the generated
// reference is current, so the documentation is an input of the test task.
tasks.test {
    inputs.files(rootProject.fileTree("docs") { include("**/*.md") }, rootProject.files("README.md"))
        .withPropertyName("documentation")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
