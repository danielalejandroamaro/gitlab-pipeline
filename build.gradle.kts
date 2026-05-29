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
        // Two internal-API touchpoints are intentional and documented:
        //   - GitLabAccountManager reuse in GitLabAuthBridge (zero own credentials).
        //   - StatusBar.removeWidget in LeftIndicatorMounter (avoid duplicate indicators).
        // Both are stable across 252/253/261/262 per the verifier report. Fail on real
        // compat problems, not on these.
        failureLevel = (FailureLevel.ALL - FailureLevel.INTERNAL_API_USAGES).toList()
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
        // via reflection at runtime; declaring it here so it's on the classpath and
        // the plugin.xml <depends> resolves.
        bundledPlugin("org.jetbrains.plugins.gitlab")

        testFramework(TestFrameworkType.Platform)
    }
}
