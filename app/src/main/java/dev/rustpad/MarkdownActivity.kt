package dev.rustpad

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/** قارئ Markdown بسيط (mdBook): عناوين، فقرات، قوائم، أكواد مع زر Try، روابط بين الفصول. */
class MarkdownActivity : AppCompatActivity() {
    private lateinit var env: Env
    private lateinit var file: File
    private var course = ""
    private lateinit var base: File      // جذر المصدر (للروابط والتضمين)
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        env = Env(this)
        CrashLogger.install(env)
        file = File(intent.getStringExtra("path") ?: run { finish(); return })
        course = intent.getStringExtra("course") ?: ""
        base = File(intent.getStringExtra("base") ?: file.parent!!)
        title = intent.getStringExtra("title") ?: file.name

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Colors.BG) }
        scroll = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(40)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val toc = if (course.isNotEmpty()) LearnContent(env).summary(course).filter { it.path != null } else emptyList()
        val cur = toc.indexOfFirst { File(base, it.path!!).canonicalPath == file.canonicalPath }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Colors.PANEL) }
        nav.addView(flatButton("◀ Prev") { if (cur > 0) goto(toc[cur - 1]) else toast("First chapter") },
            LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(flatButton("Chapters") { finish() }, LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(flatButton("Next ▶") { if (cur in 0 until toc.size - 1) goto(toc[cur + 1]) else toast("Last chapter") },
            LinearLayout.LayoutParams(0, -2, 1f))
        if (toc.isNotEmpty()) root.addView(nav)
        setContentView(root)

        try { render(expand(file.readText(), file.parentFile!!)) }
        catch (t: Throwable) { content.addView(tv("Could not display this page: $t", Colors.ERROR, 14f)) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun goto(t: TocItem) {
        open(File(base, t.path!!), t.title)
        finish()
    }

    private fun open(f: File, title: String) {
        startActivity(Intent(this, MarkdownActivity::class.java)
            .putExtra("path", f.path).putExtra("course", course).putExtra("base", base.path).putExtra("title", title))
    }

    // ---------- تضمين الملفات {{#include ...}} ----------
    private fun expand(md: String, dir: File): String {
        val rx = Regex("\\{\\{#(?:rustdoc_include|include)\\s+([^}\\s]+)[^}]*\\}\\}")
        val limit = env.prefix.canonicalPath
        return rx.replace(md) { m ->
            val parts = m.groupValues[1].split(':')
            val f = try { File(dir, parts[0]).canonicalFile } catch (_: Exception) { return@replace "" }
            if (!f.isFile || !f.path.startsWith(limit)) return@replace ""
            var lines = f.readLines()
            if (parts.size == 2) {
                val n = parts[1].toIntOrNull()
                if (n != null) lines = lines.drop(maxOf(n - 1, 0))
                else {
                    val a = lines.indexOfFirst { it.contains("ANCHOR: ${parts[1]}") }
                    val b = lines.indexOfFirst { it.contains("ANCHOR_END: ${parts[1]}") }
                    if (a >= 0 && b > a) lines = lines.subList(a + 1, b)
                }
            } else if (parts.size >= 3) {
                val st = (parts[1].toIntOrNull() ?: 1).coerceAtLeast(1)
                val en = parts[2].toIntOrNull() ?: lines.size
                lines = lines.drop(st - 1).take(maxOf(en - st + 1, 0))
            }
            lines.filter { !it.contains("ANCHOR:") && !it.contains("ANCHOR_END:") }.joinToString("\n")
        }
    }

    // ---------- العرض ----------
    private fun tv(text: CharSequence, color: Int = Colors.TEXT, size: Float = 15f) = TextView(this).apply {
        this.text = text; setTextColor(color); textSize = size
        setLineSpacing(0f, 1.15f)
    }

    private fun add(v: View, top: Int = 6, bottom: Int = 6) {
        content.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); bottomMargin = dp(bottom) })
    }

    private fun render(md: String) {
        val lines = md.replace("\r", "").split('\n')
        val para = StringBuilder()
        var i = 0
        fun flush() {
            if (para.isEmpty()) return
            add(tv(inline(para.toString().trim())).apply { movementMethod = LinkMovementMethod.getInstance(); setLinkTextColor(Color.parseColor("#6CB6FF")) })
            para.setLength(0)
        }
        val head = Regex("^(#{1,6})\\s+(.*)$")
        val item = Regex("^(\\s*)([-*+]|\\d+\\.)\\s+(.*)$")
        while (i < lines.size) {
            val l = lines[i]; val t = l.trim()
            when {
                t.startsWith("```") -> {
                    flush()
                    val lang = t.removePrefix("```").trim()
                    val body = ArrayList<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) { body.add(lines[i]); i++ }
                    codeBlock(lang, body)
                }
                t.isEmpty() -> flush()
                t.startsWith("<!--") -> { flush(); while (i < lines.size && !lines[i].contains("-->")) i++ }
                t.startsWith("<") && t.endsWith(">") && !t.contains("](") -> flush()
                head.matches(t) -> {
                    flush()
                    val m = head.find(t)!!
                    val lvl = m.groupValues[1].length
                    add(tv(inline(m.groupValues[2]), Colors.TEXT, when (lvl) { 1 -> 24f; 2 -> 20f; 3 -> 17f; else -> 15f })
                        .apply { setTypeface(null, Typeface.BOLD) }, if (lvl <= 2) 14 else 10, 4)
                }
                t.startsWith(">") -> {
                    flush()
                    val q = StringBuilder()
                    while (i < lines.size && lines[i].trim().startsWith(">")) { q.append(lines[i].trim().removePrefix(">").trim()).append(' '); i++ }
                    i--
                    add(tv(inline(q.toString().trim()), Colors.DIM, 14f).apply {
                        setPadding(dp(10), dp(4), dp(4), dp(4)); setBackgroundColor(Color.parseColor("#1C1F24"))
                        movementMethod = LinkMovementMethod.getInstance()
                    })
                }
                t.startsWith("|") -> {
                    flush()
                    val rows = ArrayList<String>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        val r = lines[i].trim()
                        if (!r.replace("|", "").replace("-", "").replace(":", "").isBlank()) rows.add(r.trim('|').split('|').joinToString(" │ ") { it.trim() })
                        i++
                    }
                    i--
                    add(HorizontalScrollView(this).apply {
                        addView(tv(rows.joinToString("\n"), Colors.TEXT, 12f).apply { typeface = Typeface.MONOSPACE })
                    })
                }
                t == "---" || t == "***" -> flush()
                item.matches(l) -> {
                    flush()
                    val m = item.find(l)!!
                    val lvl = m.groupValues[1].length / 2
                    var text = m.groupValues[3]
                    while (i + 1 < lines.size && lines[i + 1].startsWith("  ") && lines[i + 1].isNotBlank() && !item.matches(lines[i + 1]) &&
                        !lines[i + 1].trim().startsWith("```")) { text += " " + lines[i + 1].trim(); i++ }
                    val bullet = if (m.groupValues[2][0].isDigit()) m.groupValues[2] else "•"
                    add(tv(SpannableStringBuilder("$bullet ").append(inline(text))).apply {
                        setPadding(dp(8 + lvl * 16), 0, 0, 0); movementMethod = LinkMovementMethod.getInstance()
                    }, 2, 2)
                }
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(t) }
            }
            i++
        }
        flush()
    }

    private fun codeBlock(lang: String, body: List<String>) {
        val isRust = lang.isEmpty() && body.any { it.contains("fn ") || it.contains("let ") } ||
            lang.split(',').firstOrNull()?.trim() in listOf("rust", "rs")
        // أسطر rustdoc المخفية تبدأ بـ "# "
        val shown = ArrayList<String>(); val full = ArrayList<String>()
        for (l in body) {
            val hidden = isRust && (l.trim() == "#" || l.trimStart().startsWith("# "))
            val unhid = if (hidden) l.replaceFirst(Regex("#\\s?"), "") else if (isRust && l.trimStart().startsWith("##")) l.replaceFirst("##", "#") else l
            full.add(unhid)
            if (!hidden) shown.add(unhid)
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#1E1E22")) }
        box.addView(HorizontalScrollView(this).apply {
            addView(TextView(this@MarkdownActivity).apply {
                text = shown.joinToString("\n"); typeface = Typeface.MONOSPACE; textSize = 12f
                setTextColor(Color.parseColor("#D4D4D4")); setPadding(dp(10), dp(8), dp(10), dp(8))
                setTextIsSelectable(true)
            })
        })
        if (isRust) box.addView(flatButton("Try ▶") { tryCode(full.joinToString("\n")) },
            LinearLayout.LayoutParams(-2, -2).apply { gravity = android.view.Gravity.END })
        add(box, 8, 8)
    }

    /** يكتب المثال في مشروع playground ويفتحه في المحرر (نسخة احتياطية للمحتوى السابق). */
    private fun tryCode(code: String) {
        if (!env.isInstalled("base")) { toast("Toolchain not installed (Terminal → Bootstrap)"); return }
        val dir = File(env.home, "projects/playground")
        if (!File(dir, "Cargo.toml").exists()) ProjectManager(env, assets).create("playground")
        val src = File(dir, "src/main.rs")
        if (src.exists() && !src.readText().contains("Hello from RustPad")) src.copyTo(File(dir, "src/main.rs.bak"), overwrite = true)
        val body = code.trim('\n')
        src.writeText(if (Regex("fn\\s+main\\s*\\(").containsMatchIn(body)) body + "\n" else "fn main() {\n$body\n}\n")
        startActivity(Intent(this, EditorActivity::class.java).putExtra("project", dir.path))
    }

    // ---------- تنسيق داخل السطر ----------
    private fun inline(s: String): CharSequence {
        val sb = SpannableStringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) { sb.append(s[i + 1]); i += 2; continue }
            if (c == '`') {
                var n = 0; while (i + n < s.length && s[i + n] == '`') n++
                val end = s.indexOf("`".repeat(n), i + n)
                if (end > 0) {
                    val st = sb.length
                    sb.append(s.substring(i + n, end).trim())
                    sb.setSpan(TypefaceSpan("monospace"), st, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(Color.parseColor("#2A2D33")), st, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + n; continue
                }
            }
            if (c == '*' && s.startsWith("**", i)) {
                val end = s.indexOf("**", i + 2)
                if (end > 0) {
                    val st = sb.length; sb.append(inline(s.substring(i + 2, end)))
                    sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + 2; continue
                }
            }
            if (c == '*' && i + 1 < s.length && s[i + 1] != ' ') {
                val end = s.indexOf('*', i + 1)
                if (end > 0 && s[end - 1] != ' ') {
                    val st = sb.length; sb.append(inline(s.substring(i + 1, end)))
                    sb.setSpan(StyleSpan(Typeface.ITALIC), st, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + 1; continue
                }
            }
            if ((c == '[' || (c == '!' && i + 1 < s.length && s[i + 1] == '[')) ) {
                val img = c == '!'
                val ob = if (img) i + 1 else i
                val cb = s.indexOf("](", ob)
                val cp = if (cb > 0) s.indexOf(')', cb) else -1
                if (cb > 0 && cp > 0) {
                    val label = s.substring(ob + 1, cb); val url = s.substring(cb + 2, cp).trim().substringBefore(' ')
                    if (img) { sb.append("[image: ${label.ifBlank { "figure" }}]") }
                    else {
                        val st = sb.length; sb.append(inline(label))
                        sb.setSpan(object : ClickableSpan() { override fun onClick(w: View) { followLink(url) } },
                            st, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    i = cp + 1; continue
                }
            }
            if (c == '<') {
                val end = s.indexOf('>', i)
                if (end > 0 && Regex("^</?[a-zA-Z][^>]*$").matches(s.substring(i, end))) { i = end + 1; continue }
            }
            sb.append(c); i++
        }
        return sb
    }

    private fun followLink(url: String) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { toast("No browser") }
            return
        }
        val rel = url.substringBefore('#').replace(".html", ".md")
        if (rel.isEmpty() || !rel.endsWith(".md")) return
        val f = try { File(file.parentFile, rel).canonicalFile } catch (_: Exception) { return }
        if (f.isFile && f.path.startsWith(env.prefix.canonicalPath)) open(f, f.nameWithoutExtension) else toast("Page not included offline")
    }
}
