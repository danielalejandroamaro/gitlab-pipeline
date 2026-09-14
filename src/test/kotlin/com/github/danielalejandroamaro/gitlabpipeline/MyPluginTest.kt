package com.github.danielalejandroamaro.gitlabpipeline

import com.github.danielalejandroamaro.gitlabpipeline.auth.GitLabAuthBridge
import com.github.danielalejandroamaro.gitlabpipeline.model.Job
import com.github.danielalejandroamaro.gitlabpipeline.model.PipelineStatus
import com.github.danielalejandroamaro.gitlabpipeline.model.StageSummary
import com.github.danielalejandroamaro.gitlabpipeline.toolWindow.StagesStripPanel
import com.github.danielalejandroamaro.gitlabpipeline.toolWindow.computeMixedAmber
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

    fun testMixedAmberLastStageWinsByTimestamp() {
        // validate failed at T=10s but build succeeded later at T=60s → last=build (SUCCESS)
        // with an earlier FAILED stage → amber.
        val validate = job(name = "validate_tag", stage = "validate", status = PipelineStatus.FAILED,
            startedAt = "2026-06-23T10:00:00Z", finishedAt = "2026-06-23T10:00:12Z")
        val build = job(name = "build_image", stage = "build", status = PipelineStatus.SUCCESS,
            startedAt = "2026-06-23T10:00:10Z", finishedAt = "2026-06-23T10:01:00Z")
        assertTrue(computeMixedAmber(listOf(validate, build)))

        // last=FAILED → not amber (red wins).
        val build2 = build.copy(finishedAt = "2026-06-23T09:59:00Z")
        assertFalse(computeMixedAmber(listOf(validate, build2)))

        // single stage → never amber.
        assertFalse(computeMixedAmber(listOf(build)))
    }

    fun testStagesStripWrapNeverClips() {
        val stages = listOf("release", "build", "test", "deploy", "cleanup").map {
            StageSummary(it, PipelineStatus.RUNNING, listOf(job(it, it, PipelineStatus.RUNNING, null, null)))
        }
        val strip = StagesStripPanel().apply { update(stages, "release") }
        for (w in 80..700 step 7) {
            strip.setSize(w, 10)
            strip.setSize(w, strip.preferredSize.height)
            strip.doLayout()
            val bottom = strip.components.maxOf { it.y + it.height }
            assertTrue("w=$w clipped: bottom=$bottom h=${strip.height}", bottom <= strip.height)
        }
    }

    private fun job(
        name: String, stage: String, status: PipelineStatus,
        startedAt: String?, finishedAt: String?,
    ) = Job(
        id = name.hashCode().toLong(), name = name, stage = stage, status = status,
        allowFailure = false, webUrl = null, startedAt = startedAt, finishedAt = finishedAt,
        duration = null,
    )
}
