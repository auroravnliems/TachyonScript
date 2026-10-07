plugins {
    id("tachyon.java-conventions")
}

description = "In-memory test platform (testkit) and end-to-end tests of the whole pipeline."

dependencies {
    api(project(":tachyon-engine"))
    api(project(":tachyon-stdlib"))
    testRuntimeOnly(libs.sqlite.jdbc)
}

// ./gradlew :tachyon-tests:test -Ptachyon.backend=bytecode runs the whole suite on another backend.
tasks.test {
    val backend = providers.gradleProperty("tachyon.backend").orElse("")
    inputs.property("backend", backend)
    systemProperty("tachyon.test.backend", backend.get())
}
