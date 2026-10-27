pluginManagement {
    val useMirrors = providers.environmentVariable("FITSYNC_USE_MAVEN_MIRRORS")
        .map { it.toBoolean() }
        .getOrElse(true)

    repositories {
        // 本机默认使用国内镜像；GitHub 托管 runner 直接访问官方仓库。
        if (useMirrors) {
            maven(url = "https://maven.aliyun.com/repository/google")
            maven(url = "https://maven.aliyun.com/repository/central")
            maven(url = "https://maven.aliyun.com/repository/gradle-plugin")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }

    resolutionStrategy {
        eachPlugin {
            // 直接解析实际插件，避免依赖镜像中的 KSP plugin marker。
            if (requested.id.id == "com.google.devtools.ksp" && requested.version != null) {
                useModule("com.google.devtools.ksp:symbol-processing-gradle-plugin:${requested.version}")
            }
        }
    }
}

dependencyResolutionManagement {
    val useMirrors = providers.environmentVariable("FITSYNC_USE_MAVEN_MIRRORS")
        .map { it.toBoolean() }
        .getOrElse(true)

    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (useMirrors) {
            maven(url = "https://maven.aliyun.com/repository/google")
            maven(url = "https://maven.aliyun.com/repository/central")
            maven(url = "https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "FitSync"
include(":app")
include(":benchmark")
