plugins {
    `java-gradle-plugin`
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
}

gradlePlugin {
    plugins {
        create("tachyonJavaConventions") {
            id = "tachyon.java-conventions"
            implementationClass = "dev.tachyonscript.gradle.JavaConventionsPlugin"
        }
    }
}
