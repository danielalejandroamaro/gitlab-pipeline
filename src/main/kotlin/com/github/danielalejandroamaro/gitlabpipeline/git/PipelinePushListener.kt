package com.github.danielalejandroamaro.gitlabpipeline.git

import com.github.danielalejandroamaro.gitlabpipeline.services.GitLabPipelineService
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import git4idea.push.GitPushListener
import git4idea.push.GitPushRepoResult
import git4idea.repo.GitRepository

/**
 * Wakes the pipeline service whenever a successful push completes through the IDE.
 *
 * We don't try to extract the pushed tag name here because [GitPushRepoResult] only
 * exposes branch-level info reliably. Instead we delegate to
 * [GitLabPipelineService.onPushDetected] which looks for any tag pipeline that
 * popped up on GitLab since the snapshot, and follows it.
 */
class PipelinePushListener(private val project: Project) : GitPushListener {

    private val logger = thisLogger()

    override fun onCompleted(repository: GitRepository, pushResult: GitPushRepoResult) {
        if (pushResult.type == GitPushRepoResult.Type.ERROR) {
            logger.debug("Push errored, skipping pipeline follow")
            return
        }
        project.service<GitLabPipelineService>().onPushDetected()
    }
}
