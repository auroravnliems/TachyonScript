plugins {
    id("tachyon.java-conventions")
}

description = "TachyonScript engine: transactional loading, generations, event dispatch, error reporting and profiling."

dependencies {
    api(project(":tachyon-compiler"))
    api(project(":tachyon-runtime"))
    api(project(":tachyon-security"))
    // The engine implements the database part of the standard library (connections, threads).
    api(project(":tachyon-stdlib"))
}
