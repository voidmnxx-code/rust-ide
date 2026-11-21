package dev.rustpad

class Dep(val section: String, val name: String, val spec: String)

/** تعديل نصي بسيط لأقسام التبعيات في Cargo.toml مع الحفاظ على بقية الملف. */
object CargoToml {
    val SECTIONS = listOf("dependencies", "dev-dependencies", "build-dependencies")

    private fun header(t: String): String? =
        if (t.startsWith("[") && !t.startsWith("[[")) t.removePrefix("[").substringBefore("]").trim() else null

    fun list(text: String): List<Dep> {
        val res = ArrayList<Dep>()
        var sec: String? = null
        for (line in text.lines()) {
            val t = line.trim()
            val h = header(t)
            if (h != null) {
                sec = if (h in SECTIONS) h else null
                SECTIONS.firstOrNull { h.startsWith("$it.") }?.let { res.add(Dep(it, h.substringAfter('.'), "(table)")) }
                continue
            }
            if (sec != null && t.isNotEmpty() && !t.startsWith("#") && '=' in t) {
                res.add(Dep(sec, t.substringBefore('=').trim().trim('"'), t.substringAfter('=').trim()))
            }
        }
        return res
    }

    fun spec(version: String, features: List<String>, defaultFeatures: Boolean): String {
        if (features.isEmpty() && defaultFeatures) return "\"$version\""
        val sb = StringBuilder("{ version = \"$version\"")
        if (!defaultFeatures) sb.append(", default-features = false")
        if (features.isNotEmpty()) sb.append(", features = [").append(features.joinToString(", ") { "\"$it\"" }).append("]")
        return sb.append(" }").toString()
    }

    fun remove(text: String, section: String, name: String): String {
        val lines = text.split('\n')
        val out = ArrayList<String>()
        var sec: String? = null
        var skipping = false
        for (line in lines) {
            val t = line.trim()
            val h = header(t)
            if (h != null) {
                skipping = (h == "$section.$name")
                sec = if (h in SECTIONS) h else null
                if (skipping) continue
            } else if (skipping) continue
            else if (sec == section && '=' in t && !t.startsWith("#") && t.substringBefore('=').trim().trim('"') == name) continue
            out.add(line)
        }
        return out.joinToString("\n")
    }

    fun add(text: String, section: String, name: String, spec: String): String {
        val clean = remove(text, section, name)
        val lines = clean.split('\n').toMutableList()
        val at = lines.indexOfFirst { it.trim() == "[$section]" }
        if (at < 0) {
            val sb = StringBuilder(clean.trimEnd('\n'))
            sb.append("\n\n[$section]\n$name = $spec\n")
            return sb.toString()
        }
        var end = lines.size
        for (i in at + 1 until lines.size) if (header(lines[i].trim()) != null) { end = i; break }
        var ins = end
        while (ins > at + 1 && lines[ins - 1].isBlank()) ins--
        lines.add(ins, "$name = $spec")
        return lines.joinToString("\n")
    }
}
