pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "StylusMemo"
include(":app")
include(":cli")
include(":model")
include(":plugin-api")
include(":plugins:export-markdown")
include(":plugins:export-ai-markdown")
include(":plugins:memorize")
include(":plugins:highlighter")
