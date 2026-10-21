plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin { jvmToolchain(17) }

// Tests sin dependencias externas: un main() que falla con excepción si algo no cuadra.
sourceSets { test { kotlin.srcDir("src/test/kotlin") } }

tasks.register<JavaExec>("selfTest") {
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.discmount.core.SelfTestKt")
    workingDir = projectDir
    args(rootProject.file(providers.gradleProperty("imagesDir").orElse("core/build/test-images").get()).path)
}
