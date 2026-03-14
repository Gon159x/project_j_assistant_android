package com.proyectoj.assistant.features.logs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildLogsFormatterTest {
    @Test
    fun format_stripsNoiseAndSummarizesCommand() {
        val formatted = BuildLogsFormatter.format("2026-03-14 10:00:00 command: powershell -NoProfile -Command custom-task")

        assertEquals("[INFO] Ejecutando comando: powershell -NoProfile -Command custom-task", formatted)
    }

    @Test
    fun format_convertsErrorsToBlockedSummary() {
        val formatted = BuildLogsFormatter.format("ERROR: request failed with exception at C:\\repo\\file.txt")

        assertTrue(formatted!!.startsWith("[ERROR] Bloqueado por error:"))
        assertTrue(formatted.contains("<path>"))
    }

    @Test
    fun format_returnsNullForBlankInput() {
        assertNull(BuildLogsFormatter.format("   "))
    }
}
