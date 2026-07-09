package com.github.danielalejandroamaro.gitlabpipeline.api

import com.github.danielalejandroamaro.gitlabpipeline.model.Job
import com.github.danielalejandroamaro.gitlabpipeline.model.Pipeline
import com.github.danielalejandroamaro.gitlabpipeline.model.PipelineStatus
import com.github.danielalejandroamaro.gitlabpipeline.model.Release
import com.github.danielalejandroamaro.gitlabpipeline.model.ReleaseAsset
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.io.HttpRequests
import com.google.gson.JsonParser
import java.io.File
import java.net.URLEncoder

/**
 * Minimal GitLab REST v4 client. Works with self-hosted instances — the base URL
 * comes from the official JetBrains GitLab plugin's account configuration.
 */
class GitLabApiClient(
    private val serverUrl: String, // e.g. https://gitlab.example.com
    private val token: String,
) {

    private val logger = thisLogger()

    /**
     * Resolve `namespace/project` (URL-encoded) into a numeric project id. Returns Result so
     * the caller can tell a real 404 (project missing / token lacks access) from a transient
     * network failure — they deserve different UX (error balloon vs silent retry).
     */
    fun resolveProjectId(projectPath: String): Result<Long> {
        val encoded = URLEncoder.encode(projectPath, Charsets.UTF_8)
        val url = "$serverUrl/api/v4/projects/$encoded"
        return runCatching {
            val body = get(url)
            JsonParser.parseString(body).asJsonObject.get("id").asLong
        }.onFailure { logger.warn("resolveProjectId($projectPath) failed: ${it.message}") }
    }

    /**
     * Recent pipelines (default 20, newest first).
     *
     * Returns `null` on transient failure (network error, 5xx, parse error) so the caller can
     * tell apart "GitLab says zero pipelines" from "we couldn't reach GitLab" and avoid
     * blanking the UI on every blip.
     */
    fun listPipelines(projectId: Long, perPage: Int = 20): List<Pipeline>? {
        val url = "$serverUrl/api/v4/projects/$projectId/pipelines?per_page=$perPage&order_by=id&sort=desc"
        return runCatching {
            val body = get(url)
            JsonParser.parseString(body).asJsonArray.map { it.asJsonObject }.map { obj ->
                Pipeline(
                    id = obj["id"].asLong,
                    iid = obj["iid"]?.takeIf { !it.isJsonNull }?.asLong,
                    projectId = projectId,
                    status = PipelineStatus.fromRaw(obj["status"]?.takeIf { !it.isJsonNull }?.asString),
                    ref = obj["ref"]?.takeIf { !it.isJsonNull }?.asString,
                    sha = obj["sha"]?.takeIf { !it.isJsonNull }?.asString,
                    tag = obj["tag"]?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
                    webUrl = obj["web_url"]?.takeIf { !it.isJsonNull }?.asString,
                    createdAt = obj["created_at"]?.takeIf { !it.isJsonNull }?.asString,
                    updatedAt = obj["updated_at"]?.takeIf { !it.isJsonNull }?.asString,
                    duration = null,
                    source = obj["source"]?.takeIf { !it.isJsonNull }?.asString,
                )
            }
        }.onFailure { logger.warn("listPipelines($projectId) failed: ${it.message}") }
            .getOrNull()
    }

    /** Look for the newest pipeline whose ref matches the given tag (and tag==true). */
    fun findPipelineForTag(projectId: Long, tag: String): Pipeline? {
        val encoded = URLEncoder.encode(tag, Charsets.UTF_8)
        val url = "$serverUrl/api/v4/projects/$projectId/pipelines?ref=$encoded&order_by=id&sort=desc&per_page=5"
        return runCatching {
            val body = get(url)
            JsonParser.parseString(body).asJsonArray.firstOrNull()?.asJsonObject?.let { obj ->
                Pipeline(
                    id = obj["id"].asLong,
                    iid = obj["iid"]?.takeIf { !it.isJsonNull }?.asLong,
                    projectId = projectId,
                    status = PipelineStatus.fromRaw(obj["status"]?.takeIf { !it.isJsonNull }?.asString),
                    ref = obj["ref"]?.takeIf { !it.isJsonNull }?.asString,
                    sha = obj["sha"]?.takeIf { !it.isJsonNull }?.asString,
                    tag = obj["tag"]?.takeIf { !it.isJsonNull }?.asBoolean ?: true,
                    webUrl = obj["web_url"]?.takeIf { !it.isJsonNull }?.asString,
                    createdAt = obj["created_at"]?.takeIf { !it.isJsonNull }?.asString,
                    updatedAt = obj["updated_at"]?.takeIf { !it.isJsonNull }?.asString,
                    duration = null,
                    source = obj["source"]?.takeIf { !it.isJsonNull }?.asString,
                )
            }
        }.onFailure { logger.warn("findPipelineForTag($projectId,$tag) failed: ${it.message}") }
            .getOrNull()
    }

    /** Single-pipeline details (includes duration). */
    fun getPipeline(projectId: Long, pipelineId: Long): Pipeline? {
        val url = "$serverUrl/api/v4/projects/$projectId/pipelines/$pipelineId"
        return runCatching {
            val body = get(url)
            val obj = JsonParser.parseString(body).asJsonObject
            Pipeline(
                id = obj["id"].asLong,
                iid = obj["iid"]?.takeIf { !it.isJsonNull }?.asLong,
                projectId = projectId,
                status = PipelineStatus.fromRaw(obj["status"]?.takeIf { !it.isJsonNull }?.asString),
                ref = obj["ref"]?.takeIf { !it.isJsonNull }?.asString,
                sha = obj["sha"]?.takeIf { !it.isJsonNull }?.asString,
                tag = obj["tag"]?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
                webUrl = obj["web_url"]?.takeIf { !it.isJsonNull }?.asString,
                createdAt = obj["created_at"]?.takeIf { !it.isJsonNull }?.asString,
                updatedAt = obj["updated_at"]?.takeIf { !it.isJsonNull }?.asString,
                duration = obj["duration"]?.takeIf { !it.isJsonNull }?.asLong,
                source = obj["source"]?.takeIf { !it.isJsonNull }?.asString,
            )
        }.onFailure { logger.warn("getPipeline($projectId,$pipelineId) failed: ${it.message}") }
            .getOrNull()
    }

    /** Jobs of a pipeline. Used to derive stages + current stage. */
    fun listJobs(projectId: Long, pipelineId: Long): List<Job> {
        // Paginate up to ~5 pages of 100 to cover big pipelines.
        val results = mutableListOf<Job>()
        for (page in 1..5) {
            val url = "$serverUrl/api/v4/projects/$projectId/pipelines/$pipelineId/jobs" +
                "?per_page=100&page=$page&include_retried=false"
            val body = runCatching { get(url) }.onFailure {
                logger.warn("listJobs($projectId,$pipelineId) page=$page failed: ${it.message}")
            }.getOrNull() ?: break
            val arr = runCatching { JsonParser.parseString(body).asJsonArray }.getOrNull() ?: break
            if (arr.size() == 0) break
            arr.forEach { e ->
                val obj = e.asJsonObject
                val artifactsFile = obj["artifacts_file"]?.takeIf { !it.isJsonNull }?.asJsonObject
                results += Job(
                    id = obj["id"].asLong,
                    name = obj["name"]?.takeIf { !it.isJsonNull }?.asString ?: "(unnamed)",
                    stage = obj["stage"]?.takeIf { !it.isJsonNull }?.asString ?: "(no-stage)",
                    status = PipelineStatus.fromRaw(obj["status"]?.takeIf { !it.isJsonNull }?.asString),
                    allowFailure = obj["allow_failure"]?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
                    webUrl = obj["web_url"]?.takeIf { !it.isJsonNull }?.asString,
                    startedAt = obj["started_at"]?.takeIf { !it.isJsonNull }?.asString,
                    finishedAt = obj["finished_at"]?.takeIf { !it.isJsonNull }?.asString,
                    duration = obj["duration"]?.takeIf { !it.isJsonNull }?.asDouble,
                    artifactsFilename = artifactsFile?.get("filename")?.takeIf { !it.isJsonNull }?.asString,
                    artifactsSize = artifactsFile?.get("size")?.takeIf { !it.isJsonNull }?.asLong,
                )
            }
            if (arr.size() < 100) break
        }
        return results
    }

    /**
     * Raw job log. GitLab returns plain text (possibly empty/202 while the runner is provisioning).
     * Used by the tool window log panel to stream what the runner is printing in real time.
     */
    fun jobTrace(projectId: Long, jobId: Long): String? {
        val url = "$serverUrl/api/v4/projects/$projectId/jobs/$jobId/trace"
        return runCatching {
            HttpRequests.request(url)
                .tuner { conn -> conn.setRequestProperty("PRIVATE-TOKEN", token) }
                .accept("text/plain")
                .readString()
        }.onFailure { logger.warn("jobTrace($projectId,$jobId) failed: ${it.message}") }
            .getOrNull()
    }

    /**
     * Download the artifacts archive of a single job into [dest]. Returns true on success.
     * GitLab serves the zip as `application/zip`; we stream it straight to disk so big
     * artifacts don't blow up the heap.
     */
    fun downloadArtifacts(projectId: Long, jobId: Long, dest: File): Boolean {
        val url = "$serverUrl/api/v4/projects/$projectId/jobs/$jobId/artifacts"
        return runCatching {
            HttpRequests.request(url)
                .tuner { conn -> conn.setRequestProperty("PRIVATE-TOKEN", token) }
                .saveToFile(dest, null)
            true
        }.onFailure { logger.warn("downloadArtifacts($projectId,$jobId) failed: ${it.message}") }
            .getOrDefault(false)
    }

    /**
     * Releases of the project (newest first). Each release carries its source archives,
     * custom package links, and evidence files. Returns null on transient failure.
     */
    fun listReleases(projectId: Long, perPage: Int = 20): List<Release>? {
        val url = "$serverUrl/api/v4/projects/$projectId/releases?per_page=$perPage"
        return runCatching {
            val body = get(url)
            JsonParser.parseString(body).asJsonArray.map { it.asJsonObject }.map { obj ->
                val assets = obj["assets"]?.takeIf { !it.isJsonNull }?.asJsonObject
                val sources = assets?.get("sources")?.takeIf { !it.isJsonNull }?.asJsonArray
                    ?.map { it.asJsonObject }?.map { s ->
                        ReleaseAsset(
                            name = "Source code (${s["format"]?.asString ?: "?"})",
                            url = s["url"]?.asString.orEmpty(),
                            kind = com.github.danielalejandroamaro.gitlabpipeline.model.ReleaseAssetKind.SOURCE,
                        )
                    }.orEmpty()
                val links = assets?.get("links")?.takeIf { !it.isJsonNull }?.asJsonArray
                    ?.map { it.asJsonObject }?.map { l ->
                        val linkType = l["link_type"]?.takeIf { !it.isJsonNull }?.asString ?: "other"
                        val kind = if (linkType == "package")
                            com.github.danielalejandroamaro.gitlabpipeline.model.ReleaseAssetKind.PACKAGE
                        else com.github.danielalejandroamaro.gitlabpipeline.model.ReleaseAssetKind.OTHER
                        val urlStr = l["url"]?.asString.orEmpty()
                        val direct = l["direct_asset_url"]?.takeIf { !it.isJsonNull }?.asString
                        ReleaseAsset(
                            name = l["name"]?.asString ?: "(unnamed)",
                            url = urlStr,
                            kind = kind,
                            downloadUrl = direct ?: urlStr,
                        )
                    }.orEmpty()
                val evidences = obj["evidences"]?.takeIf { !it.isJsonNull }?.asJsonArray
                    ?.map { it.asJsonObject }?.map { e ->
                        ReleaseAsset(
                            name = "Evidence ${e["sha"]?.asString?.take(8) ?: ""}".trim(),
                            url = e["filepath"]?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                            kind = com.github.danielalejandroamaro.gitlabpipeline.model.ReleaseAssetKind.EVIDENCE,
                        )
                    }.orEmpty()
                Release(
                    name = obj["name"]?.asString ?: "(unnamed)",
                    tagName = obj["tag_name"]?.asString ?: "?",
                    description = obj["description"]?.takeIf { !it.isJsonNull }?.asString,
                    releasedAt = obj["released_at"]?.takeIf { !it.isJsonNull }?.asString,
                    assets = sources + links + evidences,
                )
            }
        }.onFailure { logger.warn("listReleases($projectId) failed: ${it.message}") }
            .getOrNull()
    }

    /** Stream an arbitrary URL (assumed same-host) to disk using the configured token. */
    fun downloadUrl(url: String, dest: File): Boolean =
        runCatching {
            HttpRequests.request(url)
                .tuner { conn -> conn.setRequestProperty("PRIVATE-TOKEN", token) }
                .saveToFile(dest, null)
            true
        }.onFailure { logger.warn("downloadUrl($url) failed: ${it.message}") }
            .getOrDefault(false)

    /**
     * Delete a pipeline. GitLab cascades: jobs + their artifacts go with it.
     * Returns true on 2xx. The caller is expected to refresh the list afterwards.
     */
    fun deletePipeline(projectId: Long, pipelineId: Long): Boolean {
        val url = "$serverUrl/api/v4/projects/$projectId/pipelines/$pipelineId"
        return runCatching {
            HttpRequests.request(url)
                .tuner { conn ->
                    conn.setRequestProperty("PRIVATE-TOKEN", token)
                    (conn as java.net.HttpURLConnection).requestMethod = "DELETE"
                }
                .connect { req ->
                    val code = (req.connection as java.net.HttpURLConnection).responseCode
                    code in 200..299
                }
        }.onFailure { logger.warn("deletePipeline($projectId,$pipelineId) failed: ${it.message}") }
            .getOrDefault(false)
    }

    /**
     * Delete a release by tag name. Returns true on 2xx.
     * ponytail: GitLab keeps the release's generic-package binaries in the package registry —
     * this only nukes the Release entity (metadata + links + auto evidence/source archives).
     * Upgrade path: enumerate `/projects/:id/packages?package_version=<tag>` and DELETE each
     * package id if Dno wants reclaiming the binary storage too.
     */
    fun deleteRelease(projectId: Long, tagName: String): Boolean {
        val encoded = URLEncoder.encode(tagName, Charsets.UTF_8)
        val url = "$serverUrl/api/v4/projects/$projectId/releases/$encoded"
        return runCatching {
            HttpRequests.request(url)
                .tuner { conn ->
                    conn.setRequestProperty("PRIVATE-TOKEN", token)
                    (conn as java.net.HttpURLConnection).requestMethod = "DELETE"
                }
                .connect { req ->
                    val code = (req.connection as java.net.HttpURLConnection).responseCode
                    code in 200..299
                }
        }.onFailure { logger.warn("deleteRelease($projectId,$tagName) failed: ${it.message}") }
            .getOrDefault(false)
    }

    /** Delete a tag from the project repo. Returns true on 2xx. */
    fun deleteTag(projectId: Long, tagName: String): Boolean {
        val encoded = URLEncoder.encode(tagName, Charsets.UTF_8)
        val url = "$serverUrl/api/v4/projects/$projectId/repository/tags/$encoded"
        return runCatching {
            HttpRequests.request(url)
                .tuner { conn ->
                    conn.setRequestProperty("PRIVATE-TOKEN", token)
                    (conn as java.net.HttpURLConnection).requestMethod = "DELETE"
                }
                .connect { req ->
                    val code = (req.connection as java.net.HttpURLConnection).responseCode
                    code in 200..299
                }
        }.onFailure { logger.warn("deleteTag($projectId,$tagName) failed: ${it.message}") }
            .getOrDefault(false)
    }

    private fun get(url: String): String =
        HttpRequests.request(url)
            .tuner { conn -> conn.setRequestProperty("PRIVATE-TOKEN", token) }
            .accept("application/json")
            .readString()
}
