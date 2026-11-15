plugins {
    alias(libs.plugins.kotlin.jvm)
    // src/testFixtures: a fixture game database with invented players, shared with other subprojects' tests.
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.typesafe.config)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sqlite.jdbc)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.slf4j.api)

    testFixturesImplementation(libs.sqlite.jdbc)

    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
    // sqlite-jdbc loads a native library, which Java 25 allows only when asked.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// Extracts item and quest names from an OpenRSC checkout into src/main/resources/gamedata,
// which is committed, so the ledger runs without the server's source:
//   ./gradlew :core:extractGameData -PopenrscDir=/path/to/openrsc
val extractGameData by tasks.registering(JavaExec::class) {
    description = "Extracts item and quest names from an OpenRSC checkout into src/main/resources/gamedata."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "ledger.core.gamedata.ExtractGameDataKt"
    val openrscDir = providers.gradleProperty("openrscDir")
    val outDir = layout.projectDirectory.dir("src/main/resources/gamedata").asFile.path
    // Read when the task runs, so other builds don't need the property.
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(openrscDir.orNull ?: throw GradleException("Pass the OpenRSC checkout: -PopenrscDir=/path/to/openrsc"), outDir)
    })
}
