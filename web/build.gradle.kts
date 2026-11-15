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

// The built frontend, which the admin server bundles into its jar.
val webDist by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(webDist.name, layout.buildDirectory.dir("dist")) { builtBy(buildWeb) }
}

tasks.assemble { dependsOn(buildWeb) }
