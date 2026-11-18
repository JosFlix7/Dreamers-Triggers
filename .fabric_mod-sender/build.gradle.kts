plugins {
    id("net.fabricmc.fabric-loom") version "1.18.2"
}

version = "1.0.0"
group = "dev.dreamers"

repositories {
    maven(url = "https://maven.fabricmc.net/")
}

dependencies {
    minecraft("com.mojang:minecraft:26.3")
    implementation("net.fabricmc:fabric-loader:0.20.1")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.jar {
    archiveFileName.set("dreamers-mod-sender.jar")
}
