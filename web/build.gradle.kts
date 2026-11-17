import com.github.gradle.node.npm.task.NpmTask

plugins {
    base
    alias(libs.plugins.node.gradle)
}

// Gradle downloads this exact Node into the build, so building needs only Java.
node {
    version = "26.13.1"
    download = true
    npmInstallCommand = "ci"
}

val buildWeb by tasks.registering(NpmTask::class) {
    description = "Generates the API types from the committed OpenAPI spec and builds the frontend with Vite."
    dependsOn(tasks.npmInstall)
    npmCommand = listOf("run", "build")
    inputs.dir("src")
    // Type-checked with the app, though only the tests use it.
    inputs.file("test/render.tsx")
    inputs.files("index.html", "package.json", "package-lock.json", "tsconfig.json", "vite.config.ts")
    inputs.file("../admin-server/openapi.json")
    outputs.dir(layout.buildDirectory.dir("dist"))
}

val testWeb by tasks.registering(NpmTask::class) {
    description = "Runs the web's own tests with Node's built-in test runner."
    dependsOn(tasks.npmInstall)
    // `npm test` first builds test/render.tsx for Node, so the tests can draw each page as HTML,
    // then writes each result to a JUnit report beside the Kotlin tests' reports.
    npmCommand = listOf("test")
    inputs.dir("src")
    inputs.dir("test")
    inputs.files("package.json", "package-lock.json", "vite.config.ts")
    outputs.dir(layout.buildDirectory.dir("test-results/testWeb"))
}

tasks.check { dependsOn(testWeb) }

// The day replay's real answers drawn as the "Experience gained today" board, at set moments of
// each replayed day. It runs the replay first, so like the replay it stays out of `check`.
val dayReplayPages by tasks.registering(NpmTask::class) {
    description = "Replays two invented days through the ledger, then draws the board of experience gained today from the server's real answers."
    group = "verification"
    dependsOn(tasks.npmInstall, ":admin-server:dayReplay")
    npmCommand = listOf("run", "test:dayReplay")
    environment.put("DAY_REPLAY_PAGES", rootProject.layout.projectDirectory.dir("admin-server/build/day-replay").asFile.absolutePath)
    outputs.dir(layout.buildDirectory.dir("test-results/dayReplayPages"))
    // A drawing of this run's replay: never taken from an earlier run.
    outputs.upToDateWhen { false }
}

// The built frontend, which the admin server bundles into its jar.
val webDist by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(webDist.name, layout.buildDirectory.dir("dist")) { builtBy(buildWeb) }
}

tasks.assemble { dependsOn(buildWeb) }
