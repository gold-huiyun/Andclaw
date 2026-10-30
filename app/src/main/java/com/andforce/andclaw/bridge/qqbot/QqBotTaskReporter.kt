package com.andforce.andclaw.bridge.qqbot

import com.andforce.andclaw.model.AiAction

internal class QqBotTaskReporter {
    private val entries = mutableListOf<String>()
    private var progressSent = false

    @Synchronized
    fun reset() {
        entries.clear()
        progressSent = false
    }

    @Synchronized
    fun record(role: String, content: String, action: AiAction? = null): String? {
        if (role == "ai" && action != null) {
            val progress = action.progress?.trim().orEmpty()
            val reason = action.reason?.trim().orEmpty()
            val detail = listOf(progress, reason)
                .filter(String::isNotBlank)
                .distinct()
                .joinToString("：")
                .take(MAX_ENTRY_CHARS)
            if (detail.isNotBlank()) addEntry("步骤：$detail")
            if (action.type == AiAction.TYPE_FINISH || action.type == AiAction.TYPE_ERROR || progressSent) {
                return null
            }
            progressSent = true
            return detail.ifBlank { "正在分析并执行任务" }
        }

        if (role != "system") return null
        val result = content.trim()
            .removePrefix("Action success.")
            .trim()
        if (result.isBlank() || result == "Finished." || result == "Waiting for UI refresh...") {
            return null
        }
        addEntry("结果：${result.take(MAX_ENTRY_CHARS)}")
        return null
    }

    @Synchronized
    fun buildFinalMessage(): String {
        if (entries.isEmpty()) return "任务结束，但 Agent 没有返回可用的执行结果。"
        return buildString {
            append("任务结束")
            entries.takeLast(MAX_SUMMARY_ENTRIES).forEach { entry ->
                append("\n• ")
                append(entry)
            }
        }.take(MAX_SUMMARY_CHARS)
    }

    private fun addEntry(entry: String) {
        if (entries.lastOrNull() != entry) entries += entry
        while (entries.size > MAX_SUMMARY_ENTRIES * 2) entries.removeAt(0)
    }

    private companion object {
        const val MAX_ENTRY_CHARS = 320
        const val MAX_SUMMARY_ENTRIES = 4
        const val MAX_SUMMARY_CHARS = 1400
    }
}
