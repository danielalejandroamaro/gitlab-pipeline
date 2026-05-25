package com.github.danielalejandroamaro.gitlabpipeline.api

import com.github.danielalejandroamaro.gitlabpipeline.model.Pipeline
import com.github.danielalejandroamaro.gitlabpipeline.model.PipelineStatus
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.io.HttpRequests
import com.google.gson.JsonParser
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

    /** Resolve `namespace/project` (URL-encoded) into a numeric project id. */
    fun resolveProjectId(projectPath: String): Long? {
        val encoded = URLEncoder.encode(projectPath, Charsets.UTF_8)
        val url = "$serverUrl/api/v4/projects/$encoded"
        return runCatching {
            val body = get(url)
            JsonParser.parseString(body).asJsonObject.get("id")?.asLong
        }.onFailure { logger.warn("resolveProjectId($projectPath) failed: ${it.message}") }
            .getOrNull()
    }

    /** Recent pipelines (default 20, newest first). */
    fun listPipelines(projectId: Long, perPage: Int = 20): List<Pipeline> {
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
            .getOrDefault(emptyList())
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

    private fun get(url: String): String =
        HttpRequests.request(url)
            .tuner { conn -> conn.setRequestProperty("PRIVATE-TOKEN", token) }
            .accept("application/json")
            .readString()
}
