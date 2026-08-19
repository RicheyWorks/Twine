plugins {
    `java-library`
    `maven-publish`   // Phase 9 discipline from birth: locally installable
    signing
}

group = "io.github.richeyworks"
version = "0.1.0"

java {
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

dependencies {
    api("io.github.richeyworks:smokehouse:0.1.0")

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("log4j2.loggerContextFactory",
            "org.apache.logging.log4j.simple.SimpleLoggerContextFactory")
    systemProperty("org.apache.logging.log4j.simplelog.StatusLogger.level", "OFF")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "twine"
            from(components["java"])
            pom {
                name = "Twine"
                description = "Crash-atomic multi-key batches over SmokeHouse via a journaled commit and idempotent replay."
                url = "https://github.com/RicheyWorks/Twine"
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://opensource.org/licenses/MIT"
                    }
                }
                developers {
                    developer {
                        id = "RicheyWorks"
                        name = "Richmond"
                    }
                }
                scm {
                    url = "https://github.com/RicheyWorks/Twine"
                    connection = "scm:git:https://github.com/RicheyWorks/Twine.git"
                }
            }
        }
    }
}

// Phase 9 release prep: Central requires a javadoc jar per artifact.
java {
    withJavadocJar()
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        addStringOption("Xdoclint:none", "-quiet")
    }
}

// Phase 9 release prep: PGP signing + a local staging layout for the Central Portal bundle.
// Signing activates ONLY when SIGNING_KEY is present in the environment, so everyday local
// builds stay signature-free. Stage with: ./gradlew publishMavenPublicationToStagingRepository
publishing {
    repositories {
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging-deploy"))
        }
    }
}

signing {
    val key = providers.environmentVariable("SIGNING_KEY").orNull
    val pass = providers.environmentVariable("SIGNING_PASSWORD").orNull
    isRequired = key != null
    if (key != null) {
        useInMemoryPgpKeys(key, pass)
        sign(publishing.publications["maven"])
    }
}
