plugins {
    id("tachyon.java-conventions")
}

description = "TachyonScript language frontend: source model, diagnostics, lexer, parser, syntax tree, binder."

dependencies {
    api(project(":tachyon-api"))
}
