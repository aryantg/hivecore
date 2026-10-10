pluginManagement {
    // The Android plugin markers are not always in the offline cache; map every com.android.* id to the AGP artifact.
    resolutionStrategy {
        eachPlugin {
            if (requested.id.namespace == "com.android") useModule("com.android.tools.build:gradle:${requested.version}")
        }
    }
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

rootProject.name = "HiveCore"
include(":core", ":mesh", ":trust", ":runtime", ":engine", ":planner", ":node", ":cloud", ":ledger", ":ui", ":app")
