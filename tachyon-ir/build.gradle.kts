plugins {
    id("tachyon.java-conventions")
}

description = "TachyonScript typed register IR: model, builder, printer, verifier and optimizer."

dependencies {
    api(project(":tachyon-api"))
}
