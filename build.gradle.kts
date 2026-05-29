import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

intellijPlatform {
    // No .form files or @NotNull instrumentation in this plugin — skip the
    // forms compiler / nullability bytecode rewrite step. It also dodges a
    // path-resolution bug on non-JBR JDKs (looks for $JAVA_HOME\Packages).
    instrumentCode = false

    pluginConfiguration {
        version = providers.gradleProperty("version")
        ideaVersion {
            sinceBuild = "252"
            untilBuild = provider { null }
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides {
            recommended()
        }
        // Fail the build on *any* verifier signal — including INTERNAL_API_USAGES.
        // The bridge to the official GitLab plugin types against the non-internal
        // `PersistentGitLabAccountManager` + `AccountManagerBase` chain, and the
        // status-bar de-duplication goes through `StatusBarWidgetsManager.updateWidget`
        // instead of `StatusBar.removeWidget(String)`. If a future change reintroduces
        // an internal touchpoint we want CI to block it instead of tolerating it.
        failureLevel = FailureLevel.ALL.toList()
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.2.6.2")

        // Required for tag/push detection (GitRepository, GitPushListener)
        bundledPlugin("Git4Idea")

        // Official JetBrains GitLab plugin — we reuse its account manager + tokens
        // through the public collaboration-framework types (`PersistentGitLabAccountManager`
        // + `AccountManagerBase`). Declared here so it's on the classpath and the
        // plugin.xml <depends> resolves.
        bundledPlugin("org.jetbrains.plugins.gitlab")

        testFramework(TestFrameworkType.Platform)
    }
}
