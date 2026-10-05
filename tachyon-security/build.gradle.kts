plugins {
    id("tachyon.java-conventions")
}

description = "Compiler-backed security analysis, Qwen review, policy, quarantine and audit."

dependencies {
    api(project(":tachyon-compiler"))
    testImplementation(project(":tachyon-stdlib"))
}
