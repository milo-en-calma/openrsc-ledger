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
