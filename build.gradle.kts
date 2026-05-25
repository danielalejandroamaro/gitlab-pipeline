import org.jetbrains.intellij.platform.gradle.TestFrameworkType

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
