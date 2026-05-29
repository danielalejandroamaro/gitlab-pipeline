package com.github.danielalejandroamaro.gitlabpipeline.auth

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.runBlocking
import org.jetbrains.plugins.gitlab.authentication.accounts.GitLabAccount
import org.jetbrains.plugins.gitlab.authentication.accounts.PersistentGitLabAccountManager

/**
 * Bridge to the official JetBrains GitLab plugin (`org.jetbrains.plugins.gitlab`).
 *
 * Reuses accounts the user has already configured in
 *   Settings > Version Control > GitLab
 * including self-hosted servers, so this plugin never asks for its own token.
 *
 * Types against `PersistentGitLabAccountManager` (concrete, non-`@ApiStatus.Internal`)
 * rather than the `GitLabAccountManager` interface (internal). `accountsState` and
 * `findCredentials` are inherited from `com.intellij.collaboration.auth.AccountManagerBase`,
 * which is part of the public collaboration framework — so the IntelliJ Plugin Verifier
 * does not flag these calls.
 */
object GitLabAuthBridge {

    private val logger = thisLogger()

    data class ResolvedAccount(
        val serverUrl: String, // e.g. https://gitlab.example.com (no trailing slash)
        val name: String,
        val rawAccount: GitLabAccount,
    )

    private val accountManager: PersistentGitLabAccountManager?
        get() = runCatching {
            ApplicationManager.getApplication().getService(PersistentGitLabAccountManager::class.java)
        }.onFailure { logger.warn("PersistentGitLabAccountManager unavailable: ${it.message}") }
            .getOrNull()

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
            runBlocking { manager.findCredentials(account.rawAccount) }
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
