plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass = "ledger.admin.MainKt"
    applicationName = "ledger-admin"
    // sqlite-jdbc loads a native library, which Java 25 allows only when asked.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// The frontend built by :web, served by Ktor from the classpath under web/.
val webDist by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    implementation(project(":core"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.openapi)
    implementation(libs.schema.kenerator.core)
    implementation(libs.schema.kenerator.swagger)
    implementation(libs.logback.classic)
    webDist(project(":web", "webDist"))

    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.typesafe.config)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test)
}

val webResourcesRoot = layout.buildDirectory.dir("web-resources")

val webResources by tasks.registering(Sync::class) {
    from(webDist)
    into(webResourcesRoot.map { it.dir("web") })
}

// The frontend gets a jar of its own, added only to the installed app and to `run`. Kotlin's
// test compilation depends on the main jar, so bundling it there would put an npm build in
// front of every test run.
val webJar by tasks.registering(Jar::class) {
    archiveBaseName = "ledger-web"
    from(webResourcesRoot)
    dependsOn(webResources)
}

tasks.startScripts {
    classpath = classpath!! + files(webJar)
}

distributions {
    main {
        contents {
            from(webJar) { into("lib") }
        }
    }
}

tasks.named<JavaExec>("run") {
    dependsOn(webResources)
    classpath(webResourcesRoot)
    workingDir = rootDir
}

// The tests that need the real built frontend; they run only in webTest, below.
val builtWebTests = "ledger.admin.BuiltWebTest"

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // `./gradlew :admin-server:test -PupdateOpenApi` rewrites openapi.json instead of checking it.
    systemProperty("ledger.updateOpenApi", providers.gradleProperty("updateOpenApi").isPresent)
    filter { excludeTestsMatching(builtWebTests) }
}

// The built frontend as the installed app serves it, from the same ledger-web jar. Its own task,
// so `test` doesn't wait on an npm build; `check` runs both, so the whole suite is `./gradlew check`.
val webTest by tasks.registering(Test::class) {
    description = "Tests the built frontend as the admin server serves it from the ledger-web jar."
    group = "verification"
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    // The jar first, so its web/index.html is found before the test resources' stand-in.
    classpath = files(webJar) + sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching(builtWebTests) }
    val webSource = rootProject.layout.projectDirectory.dir("web/src")
    inputs.dir(webSource)
    systemProperty("ledger.webSource", webSource.asFile.absolutePath)
}

tasks.check { dependsOn(webTest) }
