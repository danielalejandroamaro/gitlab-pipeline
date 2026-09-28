package com.github.danielalejandroamaro.gitlabpipeline

import com.github.danielalejandroamaro.gitlabpipeline.auth.GitLabAuthBridge
import com.github.danielalejandroamaro.gitlabpipeline.model.Job
import com.github.danielalejandroamaro.gitlabpipeline.model.PipelineStatus
import com.github.danielalejandroamaro.gitlabpipeline.model.StageSummary
import com.github.danielalejandroamaro.gitlabpipeline.settings.PipelineSettings
import com.github.danielalejandroamaro.gitlabpipeline.toolWindow.StagesStripPanel
import com.github.danielalejandroamaro.gitlabpipeline.toolWindow.computeMixedAmber
import com.github.danielalejandroamaro.gitlabpipeline.toolWindow.computeSiblingTags
import com.github.danielalejandroamaro.gitlabpipeline.toolWindow.formatAgo
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

    fun testSiblingTagsSameSha() {
        fun p(id: Long, ref: String, sha: String, tag: Boolean = true) = com.github.danielalejandroamaro.gitlabpipeline.model.Pipeline(
            id, null, 1, PipelineStatus.SUCCESS, ref, sha, tag, null, null, null, null, "push",
        )
        val got = computeSiblingTags(listOf(
            p(3, "v1.1.0", "abc"), p(2, "v0.5.0-oci", "abc"), p(1, "main", "abc", tag = false), p(0, "v1.0.0", "def"),
        ))
        assertEquals(listOf("v0.5.0-oci"), got[3])
        assertEquals(listOf("v1.1.0"), got[2])
        assertNull(got[1])   // branch pipeline: no badge
        assertNull(got[0])   // alone on its commit
    }

    fun testFormatAgoBuckets() {
        // Reloj fijo: el de la captura donde la web mostraba "9 hours ago".
        val now = java.time.Instant.parse("2026-09-17T18:22:41Z")
        fun ago(secondsBack: Long) = formatAgo(now.minusSeconds(secondsBack).toString(), now)

        assertEquals("just now", ago(0))
        assertEquals("just now", ago(59))
        assertEquals("1 min ago", ago(60))
        assertEquals("59 min ago", ago(3599))   // borde: nunca "60 min"
        assertEquals("1 h ago", ago(3600))
        assertEquals("9 h ago", ago(9 * 3600 + 130))
        assertEquals("23 h ago", ago(86_399))
        assertEquals("1 d ago", ago(86_400))
        assertEquals("just now", ago(-30))      // reloj del runner adelantado -> sin negativos
        assertNull(formatAgo(null, now))
        assertNull(formatAgo("no-es-fecha", now))
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

    fun testBundlesSameKeysAndPlaceholders() {
        val placeholders = Regex("""\{\d+}""")
        val base = PipelineBundle.bundleFor("en")
        for (tag in listOf("es", "zh-CN")) {
            val b = PipelineBundle.bundleFor(tag)
            assertFalse("$tag fell back to base", b.locale.toString().isEmpty())
            assertEquals("$tag keys", base.keySet(), b.keySet())
            for (k in base.keySet()) {
                assertEquals("$tag $k placeholders",
                    placeholders.findAll(base.getString(k)).map { it.value }.toSet(),
                    placeholders.findAll(b.getString(k)).map { it.value }.toSet())
            }
        }
    }

    fun testLanguageOverrideAndRawIds() {
        val s = PipelineSettings.getInstance().state
        val old = s.language
        try {
            s.language = "es"
            assertEquals("Abrir", PipelineBundle["releases.downloadResult.open"])
            assertEquals("Reintentar pipeline #27223", PipelineBundle["pipeline.menu.retry", 27223L])
            s.language = "zh-CN"
            assertEquals("打开", PipelineBundle["releases.downloadResult.open"])
            s.language = "en"
            assertEquals("Open", PipelineBundle["releases.downloadResult.open"])
        } finally {
            s.language = old
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
