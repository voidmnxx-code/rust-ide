package dev.rustpad

/** يحلّل مخرجات libtest: `test name ... ok` وكتل الفشل. */
object TestParser {
    class Result(val statuses: LinkedHashMap<String, String>, val details: Map<String, String>)

    fun parseList(text: String): List<String> =
        text.lines().map { it.trimEnd() }.filter { it.endsWith(": test") }.map { it.removeSuffix(": test") }

    fun parseRun(text: String): Result {
        val st = LinkedHashMap<String, String>()
        val det = LinkedHashMap<String, String>()
        val rx = Regex("^test (.+) \\.\\.\\. (ok|FAILED|ignored.*)$")
        var cur: String? = null
        val buf = StringBuilder()
        fun flush() { cur?.let { det[it] = buf.toString().trim() }; cur = null; buf.setLength(0) }
        for (line in text.lines()) {
            rx.matchEntire(line)?.let { m ->
                st[m.groupValues[1]] = when { m.groupValues[2] == "ok" -> "ok"; m.groupValues[2] == "FAILED" -> "fail"; else -> "ignored" }
                return@let
            }
            val h = Regex("^---- (.+) stdout ----$").matchEntire(line)
            if (h != null) { flush(); cur = h.groupValues[1]; continue }
            if (cur != null) {
                if (line.startsWith("failures:") || line.startsWith("test result:")) flush() else buf.append(line).append('\n')
            }
        }
        flush()
        return Result(st, det)
    }
}
