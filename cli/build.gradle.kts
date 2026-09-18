import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":model"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

application {
    mainClass.set("com.stylusmemo.app.cli.CliMainKt")
    applicationName = "stylus-export"
    // Japanese note titles must survive Windows' default console charset.
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
}

// ---------------------------------------------------------------------------
// Single-file fat jar: runnable with `java -jar` on any OS with a JRE 17+.
// ---------------------------------------------------------------------------
tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("stylus-export")
    archiveClassifier.set("all")
    mergeServiceFiles()
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
    manifest {
        attributes["Main-Class"] = "com.stylusmemo.app.cli.CliMainKt"
        attributes["Implementation-Version"] = project.version
    }
}

// ---------------------------------------------------------------------------
// Native packaging via jpackage (ships a trimmed JRE; no Java install needed).
// jpackage must run on the target OS: build the Linux .deb here, run the
// msi/exe task on a Windows machine.
// ---------------------------------------------------------------------------
val jpackageTool = file("${System.getProperty("java.home")}/bin/jpackage")
val appVersion = "1.0.0"
val pkgInputDir = layout.buildDirectory.dir("jpackage-input")
val pkgDestDir = layout.buildDirectory.dir("dist")

fun Exec.jpackage(type: String, vararg extra: String) {
    dependsOn(tasks.named("shadowJar"))
    workingDir(layout.buildDirectory.get())
    inputs.file(tasks.named<ShadowJar>("shadowJar").get().archiveFile)
    outputs.dir(pkgDestDir)
    environment("JAVA_HOME", System.getProperty("java.home"))
    commandLine(
        jpackageTool.absolutePath,
        "--type", type,
        "--name", "stylus-export",
        "--app-version", appVersion,
        "--vendor", "stylus-memo",
        "--input", pkgInputDir.get().asFile.absolutePath,
        "--main-jar", "stylus-export-all.jar",
        "--main-class", "com.stylusmemo.app.cli.CliMainKt",
        "--java-options", "-Dfile.encoding=UTF-8",
        "--java-options", "-Dsun.jnu.encoding=UTF-8",
        "--dest", pkgDestDir.get().asFile.absolutePath,
        *extra,
    )
}

tasks.register<Copy>("prepareJpackageInput") {
    from(tasks.named<ShadowJar>("shadowJar"))
    into(pkgInputDir)
    rename { "stylus-export-all.jar" }
}

// Builds a self-contained launcher folder for the current OS (no install).
tasks.register<Exec>("jpackageImage") {
    dependsOn("prepareJpackageInput")
    jpackage("app-image")
}

// Linux: .deb package (requires dpkg-deb + fakeroot).
tasks.register<Exec>("jpackageDeb") {
    dependsOn("prepareJpackageInput")
    jpackage("deb", "--linux-package-name", "stylus-export", "--linux-deb-maintainer", "stylus-memo")
}

// Windows: MSI installer. Run this task on a Windows machine with a JDK 17+.
tasks.register<Exec>("jpackageMsi") {
    dependsOn("prepareJpackageInput")
    jpackage("msi")
}

// Windows: EXE installer. Run this task on a Windows machine with a JDK 17+.
tasks.register<Exec>("jpackageExe") {
    dependsOn("prepareJpackageInput")
    jpackage("exe")
}
