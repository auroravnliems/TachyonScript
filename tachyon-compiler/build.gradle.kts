plugins {
    id("tachyon.java-conventions")
}

description = "TachyonScript compiler: lowering from the semantic model to IR and the compilation pipeline."

dependencies {
    api(project(":tachyon-language"))
    api(project(":tachyon-ir"))
}

dependencies {
    testImplementation(project(":tachyon-stdlib"))
}
