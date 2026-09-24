plugins {
    id("tachyon.java-conventions")
}

description = "TachyonScript standard library: Bukkit-free declarations of the language surface and pure implementations."

dependencies {
    api(project(":tachyon-api"))
}
