package com.github.danielalejandroamaro.gitlabpipeline

import com.github.danielalejandroamaro.gitlabpipeline.auth.GitLabAuthBridge
import com.github.danielalejandroamaro.gitlabpipeline.model.PipelineStatus
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MyPluginTest : BasePlatformTestCase() {

    fun testExtractProjectPathHttps() {
        assertEquals(
            "group/sub/project",
            GitLabAuthBridge.extractProjectPath("https://gitlab.example.com/group/sub/project.git"),
        )
    }

    fun testExtractProjectPathSsh() {
        assertEquals(
            "group/sub/project",
            GitLabAuthBridge.extractProjectPath("git@gitlab.example.com:group/sub/project.git"),
        )
    }

    fun testPipelineStatusTerminal() {
        assertTrue(PipelineStatus.SUCCESS.isTerminal)
        assertTrue(PipelineStatus.FAILED.isTerminal)
        assertFalse(PipelineStatus.RUNNING.isTerminal)
        assertEquals(PipelineStatus.UNKNOWN, PipelineStatus.fromRaw("nope"))
    }
}
