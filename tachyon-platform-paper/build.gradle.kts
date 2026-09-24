plugins {
    id("tachyon.java-conventions")
}

description = "Paper and Folia platform: Bukkit bindings of the standard library, event bridge, text service."

dependencies {
    api(project(":tachyon-engine"))
    api(project(":tachyon-stdlib"))
    compileOnly(libs.paper.api)
    testImplementation(libs.paper.api)
}
