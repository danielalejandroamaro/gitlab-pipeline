import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
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
            // recommended() covers IntelliJ IDEA Community + Ultimate (the default surface
            // every JB plugin must verify against). On top of that we pin the IDEs Dno
            // actually uses day-to-day so the Marketplace "Compatibility" matrix shows them.
            // select { ... } resolves the latest 2025.2.x release of each productCode so we
            // don't have to bump version strings here on every IDE release.
            recommended()
            select {
                types = listOf(
                    IntelliJPlatformType.PyCharmProfessional,
                    IntelliJPlatformType.PyCharmCommunity,
                    IntelliJPlatformType.WebStorm,
                    IntelliJPlatformType.PhpStorm,
                )
                channels = listOf(ProductRelease.Channel.RELEASE)
                sinceBuild = "252"
                untilBuild = "252.*"
            }
        }
        // Fail on real compatibility problems (missing classes, invalid plugin, broken
        // structure) but NOT on API-status signals: DEPRECATED/EXPERIMENTAL/INTERNAL
        // usages are permanently red here — the Kotlin compiler generates bridge
        // overrides for ToolWindowFactory's default methods (getIcon/getAnchor/manage,
        // flagged internal in 2025.2, experimental in 253+), so FailureLevel.ALL made
        // EVERY tag build fail regardless of regressions (v0.0.17 incident). The
        // remaining levels still block genuinely broken releases.
        failureLevel = FailureLevel.ALL.toList() - listOf(
            FailureLevel.DEPRECATED_API_USAGES,
            FailureLevel.EXPERIMENTAL_API_USAGES,
            FailureLevel.INTERNAL_API_USAGES,
        )
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
