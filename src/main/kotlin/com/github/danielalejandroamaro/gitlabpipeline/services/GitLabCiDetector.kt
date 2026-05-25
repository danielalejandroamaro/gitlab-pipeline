package com.github.danielalejandroamaro.gitlabpipeline.services

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

object GitLabCiDetector {
    /**
     * True iff there is a `.gitlab-ci.yml` at any of the project's content roots
     * (or `basePath`, as a fallback for non-module projects).
     */
    fun hasCiFile(project: Project): Boolean {
        val base = project.basePath ?: return false
        if (File(base, ".gitlab-ci.yml").isFile) return true
        // Also check VFS-known roots; helps when the file was just created.
        val vf = LocalFileSystem.getInstance().findFileByPath("$base/.gitlab-ci.yml")
        return vf != null && vf.exists() && !vf.isDirectory
    }
}
