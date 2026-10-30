plugins {
    java
}

group = "dev.dreamers"
version = "1.4"

repositories {
    maven(url = "https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.195-stable")
    compileOnly(files("libs/DiscordSRV-Build-1.30.5.jar"))
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.jar {
    archiveFileName.set("Dreamers-Triggers-$version.jar")
}
