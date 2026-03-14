package com.proyectoj.assistant.features.logs

object BuildLogsFormatter {
    fun format(rawLine: String): String? {
        var clean = ANSI_ESCAPE_REGEX.replace(rawLine, "").trim()
        if (clean.isBlank()) {
            return null
        }
        clean = LEADING_TIMESTAMP_REGEX.replace(clean, "").trim()
        clean = LEADING_NOISE_REGEX.replace(clean, "").trim()
        clean = PATH_REGEX.replace(clean, "<path>")
        clean = URL_REGEX.replace(clean, "<url>")
        clean = SHA_REGEX.replace(clean, "<sha>")
        clean = MULTISPACE_REGEX.replace(clean, " ").trim()
        if (clean.isBlank()) {
            return null
        }

        val level = classifyLogLevel(clean)
        val summary = summarizeCodexAction(clean, level)
        return "[$level] $summary"
    }

    private fun classifyLogLevel(line: String): String {
        val value = line.lowercase()
        return when {
            value.contains("error") || value.contains("failed") || value.contains("exception") -> "ERROR"
            value.contains("warn") || value.contains("retry") || value.contains("timeout") -> "WARN"
            value.contains("success") || value.contains("completed") || value.contains("done") || value.contains("passed") -> "OK"
            else -> "INFO"
        }
    }

    private fun summarizeCodexAction(line: String, level: String): String {
        val value = line.lowercase()
        val command = extractCommand(line)

        if (level == "ERROR") {
            return "Bloqueado por error: ${compactDetail(line)}"
        }

        return when {
            value.contains("apply_patch") || value.contains("patch") || value.contains("update file") ||
                value.contains("add file") || value.contains("delete file") || value.contains("edit") ->
                "Aplicando cambios de codigo"

            value.contains("rg ") || value.contains("grep") || value.contains("find ") ||
                value.contains("get-content") || value.contains("cat ") || value.contains("ls ") ||
                value.contains("scan") || value.contains("inspect") || value.contains("read") ->
                "Revisando codigo y archivos"

            value.contains("test") || value.contains("gradle") || value.contains("assemble") ||
                value.contains("build") || value.contains("compile") || value.contains("lint") ->
                "Validando build y pruebas"

            value.contains("git") || value.contains("branch") || value.contains("commit") ||
                value.contains("diff") || value.contains("status") ->
                "Revisando estado de git"

            value.contains("http") || value.contains("fetch") || value.contains("request") ||
                value.contains("download") || value.contains("endpoint") ->
                "Consultando servicios y datos"

            value.contains("plan") || value.contains("step") || value.contains("progress") ->
                "Planificando siguientes pasos"

            value.contains("done") || value.contains("completed") || value.contains("success") ||
                value.contains("passed") ->
                "Paso completado"

            command != null -> "Ejecutando comando: $command"
            else -> compactDetail(line)
        }
    }

    private fun extractCommand(line: String): String? {
        val normalized = line.trim()
        val markerIndex = normalized.indexOf("command:", ignoreCase = true)
        if (markerIndex >= 0) {
            val commandPart = normalized.substring(markerIndex + "command:".length).trim().trim('"')
            if (commandPart.isNotBlank()) {
                return compactDetail(commandPart)
            }
        }
        val psIndex = normalized.indexOf("powershell", ignoreCase = true)
        if (psIndex >= 0) {
            return "powershell"
        }
        return null
    }

    private fun compactDetail(raw: String): String {
        val cleaned = raw.trim().replace(Regex("^[\\[\\](){}\\-:]+"), "").trim()
        return if (cleaned.length > MAX_SUMMARY_DETAIL_LENGTH) {
            cleaned.take(MAX_SUMMARY_DETAIL_LENGTH).trimEnd() + "..."
        } else {
            cleaned
        }
    }

    private const val MAX_SUMMARY_DETAIL_LENGTH = 140
    private val ANSI_ESCAPE_REGEX = Regex("\\u001B\\[[;\\d]*m")
    private val LEADING_TIMESTAMP_REGEX = Regex("^\\[?\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?Z?\\]?\\s*")
    private val LEADING_NOISE_REGEX = Regex("^(?:\\[[A-Z]+\\]|[A-Z]+:|\\d+\\s*\\|)\\s*")
    private val PATH_REGEX = Regex("([A-Za-z]:\\\\[^\\s]+|/[^\\s]+)")
    private val URL_REGEX = Regex("https?://\\S+")
    private val SHA_REGEX = Regex("\\b[a-f0-9]{7,40}\\b", RegexOption.IGNORE_CASE)
    private val MULTISPACE_REGEX = Regex("\\s+")
}
