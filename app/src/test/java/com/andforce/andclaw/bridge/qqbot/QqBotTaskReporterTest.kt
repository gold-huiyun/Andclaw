package com.andforce.andclaw.bridge.qqbot

import com.andforce.andclaw.model.AiAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QqBotTaskReporterTest {

    @Test
    fun recordAction_sendsOnlyOneProgressUpdateAndKeepsResults() {
        val reporter = QqBotTaskReporter()

        val firstProgress = reporter.record(
            "ai",
            "[Progress: Opening settings]",
            AiAction(type = AiAction.TYPE_CLICK, progress = "Opening settings", reason = "Tap Settings")
        )
        val secondProgress = reporter.record(
            "ai",
            "[Progress: Reading screen]",
            AiAction(type = AiAction.TYPE_CLICK, progress = "Reading screen", reason = "Inspect the page")
        )
        reporter.record("system", "Action success.\nSettings page opened")
        reporter.record("system", "Action success. Waiting for UI refresh...")
        reporter.record("system", "Finished.")

        assertEquals("Opening settings：Tap Settings", firstProgress)
        assertNull(secondProgress)
        val summary = reporter.buildFinalMessage()
        assertTrue(summary.contains("Settings page opened"))
        assertFalse(summary.contains("Finished."))
        assertFalse(summary.contains("Waiting for UI refresh"))
    }

    @Test
    fun finishReason_isIncludedWhenThereAreNoActionResults() {
        val reporter = QqBotTaskReporter()
        reporter.record(
            "ai",
            "[Progress: Complete]",
            AiAction(type = AiAction.TYPE_FINISH, progress = "Complete", reason = "The screen shows Wi-Fi is connected")
        )

        assertTrue(reporter.buildFinalMessage().contains("The screen shows Wi-Fi is connected"))
    }
}
