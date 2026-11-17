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
    inputs.files("index.html", "package.json", "package-lock.json", "tsconfig.json", "vite.config.ts")
    inputs.file("../admin-server/openapi.json")
    outputs.dir(layout.buildDirectory.dir("dist"))
}

val testWeb by tasks.registering(NpmTask::class) {
    description = "Runs the web's own tests with Node's built-in test runner."
    dependsOn(tasks.npmInstall)
    // `npm test` also writes each result to a JUnit report beside the Kotlin tests' reports.
    npmCommand = listOf("test")
    inputs.dir("src")
    inputs.dir("test")
    inputs.file("package.json")
    outputs.dir(layout.buildDirectory.dir("test-results/testWeb"))
}

tasks.check { dependsOn(testWeb) }

// The built frontend, which the admin server bundles into its jar.
val webDist by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(webDist.name, layout.buildDirectory.dir("dist")) { builtBy(buildWeb) }
}

tasks.assemble { dependsOn(buildWeb) }
