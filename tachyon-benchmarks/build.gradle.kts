plugins {
    id("tachyon.java-conventions")
}

description = "JMH benchmarks: interpreter, event dispatch, message templates and the compiler."

dependencies {
    implementation(project(":tachyon-tests"))
    implementation(project(":tachyon-platform-paper"))
    // Adventure (MiniMessage) for the template benchmarks; Paper provides it on servers.
    implementation(libs.paper.api)
    implementation(libs.jmh.core)
    annotationProcessor(libs.jmh.annprocess)
}

// ./gradlew :tachyon-benchmarks:jmh                      all benchmarks, default settings
// ./gradlew :tachyon-benchmarks:jmh -Pjmh.include=Dispatch -Pjmh.args="-f 1 -wi 3 -i 5"
tasks.register<JavaExec>("jmh") {
    group = "benchmark"
    description = "Runs the JMH benchmarks and writes build/jmh-results.json."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "org.openjdk.jmh.Main"
    val include = providers.gradleProperty("jmh.include").orElse(".*")
    val extra = providers.gradleProperty("jmh.args").orElse("")
    val results = layout.buildDirectory.file("jmh-results.json")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(include.get()) + extra.get().split(' ').filter { it.isNotBlank() } +
                listOf("-rf", "json", "-rff", results.get().asFile.absolutePath)
    })
}
