import java.io.File

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":gcap-core"))
    implementation("org.json:json:20231013")
}

application {
    mainClass.set("org.gcap.desktop.DesktopAppKt")
}

val appName = "BAF_cap"
val appVersion = "1.0.0"

// jpackage needs a full JDK (jmods, used by jlink to build the bundled
// runtime). The system may only ship a JRE, so resolve one in this order:
//   1. -PjpackageHome=/path/to/jdk
//   2. JPACKAGE_HOME env var
//   3. tools/jdk-21 or .local/opt/jdk-21 under $HOME
//   4. any JDK under /usr/lib/jvm that has jmods
val jpackageHome: String? = run {
    val explicit = (project.findProperty("jpackageHome") as String?)
        ?: System.getenv("JPACKAGE_HOME")
    if (explicit != null) return@run explicit
    val candidates = buildList {
        add(File(System.getProperty("user.home"), "tools/jdk-21"))
        add(File(System.getProperty("user.home"), ".local/opt/jdk-21"))
        File("/usr/lib/jvm").listFiles()?.sorted()?.forEach { add(it) }
    }
    candidates.firstOrNull {
        File(it, "jmods").isDirectory && File(it, "bin/jpackage").isFile
    }?.absolutePath
}
val jpackageExe: String = jpackageHome?.let { File(it, "bin/jpackage").absolutePath } ?: "jpackage"
val jlinkDir: String? = jpackageHome?.let { File(it, "bin").absolutePath }
val packagingDir = layout.buildDirectory.dir("packaging")
val libDir = layout.buildDirectory.dir("install/desktop/lib")
val iconFile = layout.projectDirectory.file("src/main/resources/packaging/gcap.png")

val modules = listOf(
    "java.base", "java.desktop", "java.logging", "java.xml", "java.sql",
    "java.naming", "java.datatransfer", "java.prefs", "java.management",
    "jdk.unsupported", "jdk.crypto.ec", "java.net.http", "java.scripting"
).joinToString(",")

val jpackageCommon = listOf(
    "--name", appName,
    "--app-version", appVersion,
    "--vendor", "GCap",
    "--description", "Ground Call Capture - GCap Linux Desktop",
    "--input", libDir.get().asFile.absolutePath,
    "--main-jar", "desktop.jar",
    "--main-class", "org.gcap.desktop.DesktopAppKt",
    "--icon", iconFile.asFile.absolutePath,
    "--add-modules", modules,
    "--jlink-options", "--strip-debug --no-header-files --no-man-pages --compress=zip-6",
    "--java-options", "-Dfile.encoding=UTF-8"
)

fun Exec.configureJpackage() {
    jlinkDir?.let { environment("PATH", "$it:${System.getenv("PATH")}") }
}

tasks.register<Exec>("jpackageAppImage") {
    dependsOn("installDist")
    description = "Build a portable app-image with a bundled JRE."
    doFirst {
        packagingDir.get().asFile.mkdirs()
        packagingDir.get().dir(appName).asFile.deleteRecursively()
    }
    commandLine(
        listOf(
            jpackageExe, "--type", "app-image",
            "--dest", packagingDir.get().asFile.absolutePath
        ) + jpackageCommon
    )
    configureJpackage()
}

tasks.register<Exec>("jpackageDeb") {
    dependsOn("installDist")
    description = "Build an installable .deb with a bundled JRE."
    doFirst { packagingDir.get().asFile.mkdirs() }
    commandLine(
        listOf(
            jpackageExe, "--type", "deb",
            "--dest", packagingDir.get().asFile.absolutePath,
            "--linux-shortcut",
            "--linux-menu-group", "Science",
            "--linux-app-category", "science"
        ) + jpackageCommon
    )
    configureJpackage()
}

tasks.register<Exec>("distPortable") {
    dependsOn("jpackageAppImage")
    description = "Archive the app-image into a portable .tar.gz."
    val appImageDir = packagingDir.map { it.dir("$appName") }
    val outTar = packagingDir.map { it.file("$appName-$appVersion-linux-x64.tar.gz") }
    doFirst {
        outTar.get().asFile.delete()
    }
    commandLine(
        "tar", "-C", packagingDir.get().asFile.absolutePath,
        "-czf", outTar.get().asFile.absolutePath,
        appName
    )
}

tasks.register("dist") {
    dependsOn("jpackageDeb", "distPortable")
    description = "Build the .deb installer and the portable .tar.gz."
}
