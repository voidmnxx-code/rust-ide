package dev.rustpad

import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

data class Diag(val level: String, val message: String, val file: String?, val line: Int, val col: Int)

/** يشغّل cargo ويبث المخرجات، ويستخرج الأخطاء/التحذيرات (مع الملف والسطر) من JSON. */
class CargoRunner(private val env: Env) {

    fun run(
        dir: File,
        args: List<String>,
        onText: (String) -> Unit,
        onDiag: (Diag) -> Unit,
        onProcess: (Process) -> Unit,
    ): Int {
        val jobs = maxOf(2, Runtime.getRuntime().availableProcessors() / 2)
        val cmd = listOf(File(env.prefix, "bin/cargo").path) + args +
            listOf("--message-format=json", "-j", jobs.toString())
        val pb = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true)
        pb.environment().apply {
            clear()
            putAll(env.environment())
            put("CARGO_TERM_COLOR", "never")
        }
        val p = pb.start()
        onProcess(p)
        BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)).use { r ->
            while (true) {
                val line = r.readLine() ?: break
                if (!line.startsWith("{")) { onText(line + "\n"); continue }
                try { handleJson(JSONObject(line), onText, onDiag) } catch (_: Exception) { onText(line + "\n") }
            }
        }
        return p.waitFor()
    }

    private fun handleJson(o: JSONObject, onText: (String) -> Unit, onDiag: (Diag) -> Unit) {
        when (o.optString("reason")) {
            "compiler-message" -> {
                val m = o.getJSONObject("message")
                val level = m.optString("level")
                val rendered = m.optString("rendered")
                if (rendered.isNotBlank()) onText(rendered.trimEnd() + "\n\n")
                if (level == "error" || level == "warning") {
                    val spans = m.optJSONArray("spans")
                    var file: String? = null; var line = 0; var col = 0
                    if (spans != null) for (i in 0 until spans.length()) {
                        val s = spans.getJSONObject(i)
                        if (s.optBoolean("is_primary")) {
                            file = s.optString("file_name"); line = s.optInt("line_start"); col = s.optInt("column_start")
                            break
                        }
                    }
                    onDiag(Diag(level, m.optString("message"), file, line, col))
                }
            }
            "build-finished" ->
                onText(if (o.optBoolean("success")) "-- build finished OK --\n" else "-- build FAILED --\n")
        }
    }
}
