pluginManagement {
    repositories {
        maven { url = uri("https://repo.huaweicloud.com/repository/maven/") }
        maven { url = uri("https://repo.huaweicloud.com/repository/google/") }
        maven { url = uri("https://repo.huaweicloud.com/repository/gradle-plugin/") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://repo.huaweicloud.com/repository/maven/") }
        maven { url = uri("https://repo.huaweicloud.com/repository/google/") }
        google()
        mavenCentral()
    }
}
rootProject.name = "TianXianQuant"
include(":app")
