pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Composite build: consume the local SDK as a Gradle project at ../../.
// Edits to the SDK are picked up on the next sample build with no publish.
includeBuild("../../") {
    dependencySubstitution {
        substitute(module("com.gtmeasy:growth"))
            .using(project(":growth"))
    }
}

rootProject.name = "twilar-sample"
include(":app")
