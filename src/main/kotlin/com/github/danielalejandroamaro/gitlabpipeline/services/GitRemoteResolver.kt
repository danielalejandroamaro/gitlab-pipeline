package com.github.danielalejandroamaro.gitlabpipeline.services

import com.github.danielalejandroamaro.gitlabpipeline.auth.GitLabAuthBridge
import com.github.danielalejandroamaro.gitlabpipeline.settings.PipelineProjectSettings
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager

data class GitLabRemote(
    val url: String,
    val projectPath: String, // group/sub/project
)

object GitRemoteResolver {

    /**
     * False while git4idea is still initializing repos at IDE startup. Callers treat that as
     * transient ("retry next tick"), not as a missing-remote configuration error.
     */
    fun reposLoaded(project: Project): Boolean =
        GitRepositoryManager.getInstance(project).repositories.isNotEmpty()

    /** Every GitLab-shaped remote URL of every repo in the project, in git4idea order. */
    fun candidates(project: Project): List<GitLabRemote> =
        GitRepositoryManager.getInstance(project).repositories.flatMap { repo ->
            repo.remotes.flatMap { remote ->
                remote.urls.mapNotNull { url ->
                    val path = GitLabAuthBridge.extractProjectPath(url) ?: return@mapNotNull null
                    GitLabRemote(url = url, projectPath = path)
                }
            }
        }.distinctBy { it.url }

    /**
     * Pick the remote to watch. A remote chosen by the user in Settings wins (while it still
     * exists); otherwise: first remote whose host matches a configured GitLab account, falling
     * back to "origin" so users on the very first run still see something.
     */
    fun resolve(project: Project): GitLabRemote? {
        val repos = GitRepositoryManager.getInstance(project).repositories
        if (repos.isEmpty()) return null

        val candidates = candidates(project)
        if (candidates.isEmpty()) return null

        PipelineProjectSettings.getInstance(project).preferredRemoteUrl?.let { preferred ->
            candidates.firstOrNull { it.url == preferred }?.let { return it }
            // preferido desaparecido (remote borrado/renombrado): cae al auto sin romper
        }

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
