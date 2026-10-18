package dev.playday3008.llmhealthbridge.send

import android.content.pm.PackageManager

/** An LLM chat app that can receive the shared photos. */
data class LlmApp(val packageName: String, val label: String)

/** The supported LLM apps. */
object LlmApps {
    /** Supported apps in chip order. */
    val known = listOf(
        LlmApp("com.anthropic.claude", "Claude"),
        LlmApp("com.openai.chatgpt", "ChatGPT"),
        LlmApp("com.google.android.apps.bard", "Gemini"),
        LlmApp("com.deepseek.chat", "DeepSeek"),
        LlmApp("ai.mistral.chat", "Le Chat"),
        LlmApp("ai.perplexity.app.android", "Perplexity")
    )

    /** The subset of [known] installed on the device, in the same order. */
    fun installed(pm: PackageManager): List<LlmApp> = known.filter {
        try {
            pm.getPackageInfo(it.packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
