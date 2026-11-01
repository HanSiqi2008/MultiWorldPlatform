plugins {
    java
}

group = "org.multiworldplatform"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://central.sonatype.com/repository/maven-snapshots/")
    maven("https://repo.spongepowered.org/repository/maven-public/")
}

dependencies {
    compileOnly("org.spongepowered:spongeapi:12.1.0-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-g")
}

tasks.processResources {
    expand("apiVersion" to "12.1.0")
}

tasks.jar {
    archiveBaseName.set("MultiWorldPlatform")
    manifest {
        attributes["Implementation-Title"] = "MultiWorldPlatform"
        attributes["Implementation-Version"] = project.version
    }
}
