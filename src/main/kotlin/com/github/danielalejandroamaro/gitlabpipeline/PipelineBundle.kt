package com.github.danielalejandroamaro.gitlabpipeline

import com.github.danielalejandroamaro.gitlabpipeline.settings.PipelineSettings
import com.intellij.AbstractBundle
import com.intellij.DynamicBundle
import com.intellij.openapi.application.ApplicationManager
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey
import java.util.Locale
import java.util.ResourceBundle

@NonNls
private const val BUNDLE = "messages.PipelineBundle"

/**
 * Plugin strings: English (base), Spanish (`_es`) and Simplified Chinese (`_zh_CN`).
 * "Auto" follows the IDE language (DynamicBundle); an explicit choice in Settings overrides it,
 * because the IDE can't be switched to Spanish (no JetBrains language pack for it).
 */
object PipelineBundle : DynamicBundle(PipelineBundle::class.java, BUNDLE) {

    operator fun get(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any) =
        msg(key, params)

    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any) =
        msg(key, params)

    /** Language tags offered in Settings; "" = follow the IDE. */
    val LANGUAGES = listOf("", "en", "es", "zh-CN")

    private fun msg(key: String, params: Array<out Any>): String {
        // MessageFormat would render ids as "27,223"; plain strings keep them verbatim.
        val args = params.map { if (it is Number) it.toString() else it }.toTypedArray()
        val tag = if (ApplicationManager.getApplication() == null) "" else PipelineSettings.getInstance().state.language
        if (tag.isBlank()) return getMessage(key, *args)
        return AbstractBundle.message(bundleFor(tag), key, *args)
    }

    /** No-fallback control: "en" must load the base file, not the JVM default locale's. */
    fun bundleFor(tag: String): ResourceBundle = ResourceBundle.getBundle(
        BUNDLE, Locale.forLanguageTag(tag), PipelineBundle::class.java.classLoader,
        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES),
    )
}
