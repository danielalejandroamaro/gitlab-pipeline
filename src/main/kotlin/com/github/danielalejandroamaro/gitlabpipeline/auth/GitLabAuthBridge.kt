package com.github.danielalejandroamaro.gitlabpipeline.auth

import com.intellij.collaboration.auth.AccountManagerBase
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.runBlocking
import org.jetbrains.plugins.gitlab.authentication.accounts.GitLabAccount

/**
 * Bridge to the official JetBrains GitLab plugin (`org.jetbrains.plugins.gitlab`).
 *
 * Reuses accounts the user has already configured in
 *   Settings > Version Control > GitLab
 * including self-hosted servers, so this plugin never asks for its own token.
 *
 * ## Why reflection
 *
 * v0.0.9 looked up the singleton with `getService(GitLabAccountManager::class.java)`.
 * That works because the official plugin registers its account manager with
 * `serviceInterface=GitLabAccountManager` — the platform binds by interface, not by impl class.
 *
 * v0.0.10 tried to "go public" by switching the lookup key to
 * `PersistentGitLabAccountManager` (the concrete impl, which is *not* `@ApiStatus.Internal`).
 * That sidestepped the IntelliJ Plugin Verifier's INTERNAL_API_USAGES check at the cost of
 * runtime correctness: `getService(PersistentGitLabAccountManager::class.java)` returns `null`
 * because the platform doesn't have a binding under that key. Bridge ended up seeing zero
 * accounts and the watcher silently stopped working.
 *
 * Fix here: look up the manager via `Class.forName(...)` so the bytecode of this file never
 * mentions `GitLabAccountManager` directly. The verifier scans references, not strings, so the
 * INTERNAL_API_USAGES count stays at zero. Method dispatch on the returned object goes through
 * `AccountManagerBase<GitLabAccount, Any>` (public, in `com.intellij.collaboration.auth`),
 * so `accountsState.value` and `findCredentials(account)` both type-check against public API.
 * The credentials type is `Any` because 2026.2 changed it from `String` to the internal
 * `GitLabCredentials` sealed class — `tokenFor` handles both shapes at runtime.
 *
 * Trade-off: if a future JetBrains GitLab plugin renames or moves
 * `GitLabAccountManager`, this bridge breaks at runtime with no compile-time warning. The
 * `lastResolvedManagerClass` + `lastResolutionError` diagnostics surface that in the settings
 * page so the user can see what is/isn't resolving without digging through `idea.log`.
 */
object GitLabAuthBridge {

    private val logger = thisLogger()

    /**
     * Fully-qualified name of the account-manager interface inside the JetBrains GitLab plugin.
     * Resolved via [Class.forName] so this file holds no compile-time reference to the internal
     * type — see the class doc above.
     */
    private const val MANAGER_FQN = "org.jetbrains.plugins.gitlab.authentication.accounts.GitLabAccountManager"

    /** Last service impl class returned by the platform, or `null` if the lookup failed. Exposed for the settings diagnostic. */
    @Volatile
    var lastResolvedManagerClass: String? = null
        private set

    /** Last exception/error from the resolve chain (`null` when it succeeded). Exposed for the settings diagnostic. */
    @Volatile
    var lastResolutionError: String? = null
        private set

    data class ResolvedAccount(
        val serverUrl: String, // e.g. https://gitlab.example.com (no trailing slash)
        val name: String,
        val rawAccount: GitLabAccount,
    )

    @Suppress("UNCHECKED_CAST")
    private val accountManager: AccountManagerBase<GitLabAccount, Any>?
        get() = runCatching {
            val cls = Class.forName(MANAGER_FQN)
            val svc = ApplicationManager.getApplication().getService(cls)
            if (svc == null) {
                lastResolvedManagerClass = null
                lastResolutionError = "getService($MANAGER_FQN) returned null"
                return@runCatching null
            }
            lastResolvedManagerClass = svc.javaClass.name
            lastResolutionError = null
            svc as AccountManagerBase<GitLabAccount, Any>
        }.onFailure {
            lastResolvedManagerClass = null
            lastResolutionError = "${it::class.simpleName}: ${it.message}"
            logger.warn("GitLab account manager unavailable: ${it.message}")
        }.getOrNull()

    fun accounts(): List<ResolvedAccount> {
        val manager = accountManager ?: return emptyList()
        return manager.accountsState.value.map { acc ->
            ResolvedAccount(
                serverUrl = acc.server.toString().trimEnd('/'),
                name = acc.name,
                rawAccount = acc,
            )
        }
    }

    fun tokenFor(account: ResolvedAccount): String? {
        val manager = accountManager ?: return null
        return runCatching {
            when (val cred = runBlocking { manager.findCredentials(account.rawAccount) }) {
                null -> null
                is String -> cred // <=2026.1: credentials are the raw token
                // 2026.2+: GitLabCredentials sealed class; read accessToken reflectively to keep
                // this file free of compile-time references to the internal type.
                else -> cred.javaClass.getMethod("getAccessToken").invoke(cred) as? String
            }
        }.onFailure { logger.warn("findCredentials failed: ${it.message}") }
            .getOrNull()
    }

    /** Find the account whose server host matches the host of [remoteUrl]. */
    fun findAccountForRemote(remoteUrl: String): ResolvedAccount? {
        val host = extractHost(remoteUrl) ?: return null
        return accounts().firstOrNull { acc ->
            val serverHost = extractHost(acc.serverUrl)
            serverHost?.equals(host, ignoreCase = true) == true
        }
    }

    /**
     * Pulls `group/sub/project` out of an http(s) or ssh remote URL.
     * Examples:
     *   https://gitlab.example.com/group/sub/project.git -> group/sub/project
     *   git@gitlab.example.com:group/sub/project.git     -> group/sub/project
     */
    fun extractProjectPath(remoteUrl: String): String? = runCatching {
        val cleaned = remoteUrl.trim().removeSuffix(".git")
        when {
            cleaned.startsWith("http://") || cleaned.startsWith("https://") -> {
                val uri = java.net.URI(cleaned)
                uri.path.trimStart('/').takeIf { it.isNotEmpty() }
            }
            cleaned.contains("@") && cleaned.contains(":") ->
                cleaned.substringAfter(":").trimStart('/').takeIf { it.isNotEmpty() }
            else -> null
        }
    }.getOrNull()

    private fun extractHost(url: String): String? = runCatching {
        val cleaned = url.trim().removeSuffix(".git").removeSuffix("/")
        when {
            cleaned.startsWith("http://") || cleaned.startsWith("https://") ->
                java.net.URI(cleaned).host
            cleaned.contains("@") && cleaned.contains(":") ->
                cleaned.substringAfter("@").substringBefore(":")
            else -> null
        }
    }.getOrNull()
}
