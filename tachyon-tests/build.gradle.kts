plugins {
    id("tachyon.java-conventions")
}

description = "In-memory test platform (testkit) and end-to-end tests of the whole pipeline."

dependencies {
    api(project(":tachyon-engine"))
    api(project(":tachyon-stdlib"))
}
