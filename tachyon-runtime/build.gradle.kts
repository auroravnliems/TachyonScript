plugins {
    id("tachyon.java-conventions")
}

description = "TachyonScript runtime: assembler, linker, interpreter, errors and event handler tables."

dependencies {
    api(project(":tachyon-ir"))
    testImplementation(project(":tachyon-compiler"))
    testImplementation(project(":tachyon-stdlib"))
}
