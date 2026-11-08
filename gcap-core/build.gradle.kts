plugins {
    alias(libs.plugins.kotlin.jvm)
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
    implementation("org.json:json:20231013")
    implementation("org.apache.poi:poi-ooxml:5.2.3")
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // Phone-like heap: an unbounded IntArray sized from a file-controlled count
    // then fails here instead of silently succeeding on a large build machine.
    maxHeapSize = "256m"
}