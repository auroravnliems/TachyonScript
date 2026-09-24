plugins {
    id("tachyon.java-conventions")
}

description = "TachyonScript engine: transactional loading, generations, event dispatch, error reporting and profiling."

dependencies {
    api(project(":tachyon-compiler"))
    api(project(":tachyon-runtime"))
}
