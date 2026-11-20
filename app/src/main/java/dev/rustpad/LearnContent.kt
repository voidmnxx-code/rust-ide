package dev.rustpad

import android.content.Context
import org.json.JSONArray
import java.io.File

class RlExercise(val name: String, val dir: String, val test: Boolean, val hint: String)
class ExItem(val slug: String, val name: String, val difficulty: Int)
class TocItem(val title: String, val path: String?, val level: Int)

class LearnProgress(ctx: Context) {
    private val sp = ctx.getSharedPreferences("learn", Context.MODE_PRIVATE)
    fun done(course: String): Set<String> = sp.getStringSet("done:$course", emptySet()) ?: emptySet()
    fun mark(course: String, id: String) {
        val s = HashSet(done(course)); s.add(id)
        sp.edit().putStringSet("done:$course", s).apply()
    }
}

/** المحتوى التعليمي المثبّت (مرحلة learn) ونسخ العمل داخل ~/learn. */
class LearnContent(private val env: Env) {
    val root = File(env.prefix, "share/rustpad/learn")
    val work = File(env.home, "learn")

    fun installed() = File(root, "VERSIONS.json").exists()

    private fun unq(s: String) = s.trim().removeSurrounding("\"")

    fun rustlings(): List<RlExercise> {
        val f = File(root, "rustlings/info.toml")
        if (!f.exists()) return emptyList()
        val lines = f.readLines()
        val res = ArrayList<RlExercise>()
        var name = ""; var dir = ""; var test = true; var hint = ""; var inEx = false
        fun flush() {
            if (inEx && name.isNotEmpty()) res.add(RlExercise(name, dir, test, hint))
            name = ""; dir = ""; test = true; hint = ""
        }
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.trim() == "[[exercises]]") { flush(); inEx = true }
            else if (inEx) {
                when {
                    l.startsWith("name = ") -> name = unq(l.substringAfter("= "))
                    l.startsWith("dir = ") -> dir = unq(l.substringAfter("= "))
                    l.startsWith("test = ") -> test = !l.contains("false")
                    l.startsWith("hint = ") -> {
                        val rest = l.substringAfter("hint = ").trim()
                        if (rest.startsWith("\"\"\"")) {
                            val first = rest.removePrefix("\"\"\"")
                            if (first.length >= 3 && first.endsWith("\"\"\"")) hint = first.dropLast(3)
                            else {
                                val sb = StringBuilder(first).append('\n')
                                i++
                                while (i < lines.size) {
                                    val x = lines[i]
                                    if (x.contains("\"\"\"")) { sb.append(x.substringBefore("\"\"\"")); break }
                                    sb.append(x).append('\n'); i++
                                }
                                hint = sb.toString().trim('\n')
                            }
                        } else hint = unq(rest)
                    }
                }
            }
            i++
        }
        flush()
        return res
    }

    fun rustlingsFile(ex: RlExercise) = File(work, "rustlings/exercises/${ex.dir}/${ex.name}.rs")
    fun rustlingsOriginal(ex: RlExercise) = File(root, "rustlings/exercises/${ex.dir}/${ex.name}.rs")
    fun rustlingsSolution(ex: RlExercise) = File(root, "rustlings/solutions/${ex.dir}/${ex.name}.rs")

    /** نسخة العمل (تُنشأ عند أول فتح)، يعاد نسخها من الأصل إن reset. */
    fun ensureRustlings(ex: RlExercise, reset: Boolean = false): File {
        val f = rustlingsFile(ex)
        if (reset || !f.exists()) {
            f.parentFile?.mkdirs()
            rustlingsOriginal(ex).copyTo(f, overwrite = true)
        }
        return f
    }

    fun exercism(): List<ExItem> {
        val f = File(root, "exercism/index.json")
        if (!f.exists()) return emptyList()
        val a = JSONArray(f.readText())
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            ExItem(o.getString("slug"), o.getString("name"), o.optInt("difficulty", 1))
        }.filter { File(root, "exercism/practice/${it.slug}").isDirectory }
    }

    /** مشروع cargo للتمرين داخل ~/learn/exercism/<slug> (يُنسخ مرة واحدة). */
    fun ensureExercism(slug: String): File {
        val dst = File(work, "exercism/$slug")
        if (!File(dst, "Cargo.toml").exists()) {
            val src = File(root, "exercism/practice/$slug")
            dst.mkdirs()
            for (sub in listOf(".docs", "src", "tests")) {
                val s = File(src, sub)
                if (s.isDirectory) s.copyRecursively(File(dst, sub), overwrite = true)
            }
            File(src, "Cargo.toml").copyTo(File(dst, "Cargo.toml"), overwrite = true)
            File(dst, ".gitignore").writeText("target/\n")
        }
        File(dst, ".exercism").writeText(slug)
        return dst
    }

    fun exercismExample(slug: String) = File(root, "exercism/practice/$slug/.meta/example.rs")

    /** فهرس كتاب mdBook من SUMMARY.md. */
    fun summary(course: String): List<TocItem> {
        val f = File(root, "$course/src/SUMMARY.md")
        if (!f.exists()) return emptyList()
        val rx = Regex("^(\\s*)(?:[-*]\\s+)?\\[(.+?)\\]\\((.*?)\\)\\s*$")
        val raw = f.readLines()
        var unit = 0
        for (l in raw) rx.find(l)?.let { val n = it.groupValues[1].length; if (n > 0 && (unit == 0 || n < unit)) unit = n }
        if (unit == 0) unit = 2
        val res = ArrayList<TocItem>()
        for (l in raw) {
            if (l.startsWith("# ") && !l.startsWith("# Summary")) { res.add(TocItem(l.removePrefix("# ").trim(), null, 0)); continue }
            val m = rx.find(l) ?: continue
            val path = m.groupValues[3].trim()
            if (path.isEmpty() || !path.endsWith(".md")) continue
            res.add(TocItem(m.groupValues[2], path, m.groupValues[1].length / unit))
        }
        return res
    }

    companion object {
        val COURSES = listOf(
            Triple("rustlings", "Rustlings", "95 small exercises: fix the code until it compiles and passes"),
            Triple("exercism", "Exercism: Rust track", "Practice problems with tests (cargo test)"),
            Triple("book", "The Rust Programming Language", "The official book"),
            Triple("rbe", "Rust by Example", "Learn Rust through runnable examples"),
            Triple("rbp", "Rust by Practice", "Exercises inside the text (tap Try to run them)"),
        )
    }
}
