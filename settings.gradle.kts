pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "LumixFilmMirror"
include(":app", ":core:model", ":core:design", ":core:media", ":core:camera", ":core:storage", ":core:transfer")
