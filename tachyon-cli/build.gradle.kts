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

// The tests compile every example in the documentation and the wiki and check that the
// generated reference is current, so the documentation is an input of the test task.
// The wiki is its own repository (TachyonScript.wiki): the tests read it from wiki/ next to the
// sources, or from the checkout given with -Ptachyon.wiki=<folder>; without one they skip it.
val wiki = rootProject.file(providers.gradleProperty("tachyon.wiki").getOrElse("wiki"))
tasks.test {
    systemProperty("tachyon.wiki", wiki.absolutePath)
    inputs.files(rootProject.fileTree("docs") { include("**/*.md") }, fileTree(wiki) { include("**/*.md") },
        rootProject.files("README.md"))
        .withPropertyName("documentation")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
