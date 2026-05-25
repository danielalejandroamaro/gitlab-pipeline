package com.github.danielalejandroamaro.gitlabpipeline.services

import com.github.danielalejandroamaro.gitlabpipeline.auth.GitLabAuthBridge
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager

data class GitLabRemote(
    val url: String,
    val projectPath: String, // group/sub/project
)

object GitRemoteResolver {

    /**
     * Pick the first git remote whose host matches a configured GitLab account.
     * Falls back to "origin" when no account match is found, so users on the very
     * first run still see something.
     */
    fun resolve(project: Project): GitLabRemote? {
        val repos = GitRepositoryManager.getInstance(project).repositories
        if (repos.isEmpty()) return null

        val candidates = repos.flatMap { repo ->
            repo.remotes.flatMap { remote ->
                remote.urls.mapNotNull { url ->
                    val path = GitLabAuthBridge.extractProjectPath(url) ?: return@mapNotNull null
                    GitLabRemote(url = url, projectPath = path)
                }
            }
        }
        if (candidates.isEmpty()) return null

        // Prefer remotes that match a configured account.
        val withAccount = candidates.firstOrNull { GitLabAuthBridge.findAccountForRemote(it.url) != null }
        if (withAccount != null) return withAccount

        // Else prefer "origin".
        val origin = repos.firstNotNullOfOrNull { repo ->
            repo.remotes.firstOrNull { it.name == "origin" }?.urls?.firstNotNullOfOrNull { url ->
                GitLabAuthBridge.extractProjectPath(url)?.let { GitLabRemote(url, it) }
            }
        }
        return origin ?: candidates.first()
    }
}
