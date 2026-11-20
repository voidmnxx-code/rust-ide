package dev.rustpad

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/** مركز التعلّم: قائمة الدورات ثم محتوى كل دورة. */
class LearnActivity : AppCompatActivity() {
    private class Row(val title: String, val sub: String = "", val indent: Int = 0,
                       val header: Boolean = false, val done: Boolean = false, val action: (() -> Unit)? = null)

    private lateinit var env: Env
    private lateinit var content: LearnContent
    private lateinit var list: ListView
    private lateinit var banner: TextView
    private var course: String? = null
    private var rows: List<Row> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Colors.load(this)
        env = Env(this)
        CrashLogger.install(env)
        content = LearnContent(env)
        course = intent.getStringExtra("course")
        title = LearnContent.COURSES.firstOrNull { it.first == course }?.second ?: "Learn Rust"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Colors.BG); setPadding(dp(12), dp(8), dp(12), dp(8)) }
        banner = TextView(this).apply { setTextColor(Colors.WARN); textSize = 13f; setPadding(0, dp(6), 0, dp(6)) }
        root.addView(banner)
        list = ListView(this).apply { setBackgroundColor(Colors.BG); divider = null }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val pos = list.firstVisiblePosition
        refresh()
        list.setSelection(pos)
    }

    private fun refresh() {
        if (!content.installed()) {
            banner.text = "Learning content is not installed yet.\nOpen Terminal and tap [Bootstrap] (the 'learn' stage, a few MB), then come back."
            rows = emptyList(); list.adapter = null; return
        }
        banner.text = ""
        rows = when (course) {
            null -> hub()
            "rustlings" -> rustlingsRows()
            "exercism" -> exercismRows()
            else -> bookRows(course!!)
        }
        list.adapter = object : ArrayAdapter<Row>(this, android.R.layout.simple_list_item_2, rows) {
            override fun getView(p: Int, v: View?, parent: ViewGroup): View {
                val r = rows[p]
                val box = (v as? LinearLayout) ?: LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                box.removeAllViews()
                box.setPadding(dp(8 + r.indent * 14), dp(if (r.header) 14 else 9), dp(8), dp(if (r.header) 4 else 9))
                box.addView(TextView(context).apply {
                    text = (if (r.done) "✓  " else "") + r.title
                    setTextColor(if (r.header) Colors.DIM else if (r.done) Colors.DIM else Colors.TEXT)
                    textSize = if (r.header) 12f else 16f
                    if (r.header) setTypeface(null, Typeface.BOLD)
                })
                if (r.sub.isNotEmpty()) box.addView(TextView(context).apply { text = r.sub; setTextColor(Colors.DIM); textSize = 12f })
                return box
            }
        }
        list.setOnItemClickListener { _, _, i, _ -> rows[i].action?.invoke() }
    }

    // ---------- المركز ----------
    private fun hub(): List<Row> {
        val prog = LearnProgress(this)
        val rl = content.rustlings().size
        val ex = content.exercism().size
        val res = ArrayList<Row>()
        for ((id, name, sub) in LearnContent.COURSES) {
            val extra = when (id) {
                "rustlings" -> "  ${prog.done(id).size}/$rl"
                "exercism" -> "  ${prog.done(id).size}/$ex"
                else -> ""
            }
            res.add(Row(name + extra, sub) {
                startActivity(Intent(this, LearnActivity::class.java).putExtra("course", id))
            })
        }
        res.add(Row("Credits", "Content © its authors: rustlings (MIT), Exercism Rust track (MIT), The Rust Book and Rust by Example (MIT/Apache-2.0), " +
            "Rust by Practice (CC BY 4.0, by sunface). Bundled unmodified for offline study.", header = false))
        return res
    }

    // ---------- rustlings ----------
    private fun rustlingsRows(): List<Row> {
        val done = LearnProgress(this).done("rustlings")
        val res = ArrayList<Row>()
        var lastDir = ""
        for (e in content.rustlings()) {
            if (e.dir != lastDir) { lastDir = e.dir; res.add(Row(e.dir.substringAfter('_').replace('_', ' ').uppercase(), header = true)) }
            res.add(Row(e.name, if (e.test) "tests" else "", done = e.name in done) { openRustlings(e) })
        }
        return res
    }

    private fun openRustlings(e: RlExercise) {
        if (!env.isInstalled("base")) { Toast.makeText(this, "Toolchain not installed (Terminal → Bootstrap)", Toast.LENGTH_LONG).show(); return }
        try {
            val f = content.ensureRustlings(e)
            startActivity(Intent(this, EditorActivity::class.java)
                .putExtra("project", File(content.work, "rustlings").path)
                .putExtra("file", f.path).putExtra("ex_course", "rustlings").putExtra("ex_id", e.name))
        } catch (t: Throwable) { Toast.makeText(this, "Cannot open: $t", Toast.LENGTH_LONG).show() }
    }

    // ---------- exercism ----------
    private fun exercismRows(): List<Row> {
        val done = LearnProgress(this).done("exercism")
        val res = ArrayList<Row>()
        var last = -1
        val names = mapOf(1 to "EASY", 2 to "EASY+", 3 to "MEDIUM", 4 to "MEDIUM+", 5 to "HARD", 6 to "HARD+", 7 to "HARDER", 8 to "HARDER", 9 to "EXPERT", 10 to "EXPERT")
        for (e in content.exercism()) {
            if (e.difficulty != last) { last = e.difficulty; res.add(Row("${names[e.difficulty] ?: "LEVEL"}  (difficulty ${e.difficulty})", header = true)) }
            res.add(Row(e.name, e.slug, done = e.slug in done) { openExercism(e) })
        }
        return res
    }

    private fun openExercism(e: ExItem) {
        if (!env.isInstalled("base")) { Toast.makeText(this, "Toolchain not installed (Terminal → Bootstrap)", Toast.LENGTH_LONG).show(); return }
        try {
            val dir = content.ensureExercism(e.slug)
            val lib = File(dir, "src/lib.rs")
            startActivity(Intent(this, EditorActivity::class.java)
                .putExtra("project", dir.path).putExtra("file", lib.path)
                .putExtra("ex_course", "exercism").putExtra("ex_id", e.slug))
        } catch (t: Throwable) { Toast.makeText(this, "Cannot open: $t", Toast.LENGTH_LONG).show() }
    }

    // ---------- الكتب ----------
    private fun bookRows(id: String): List<Row> {
        val base = File(content.root, "$id/src")
        return content.summary(id).map { t ->
            if (t.path == null) Row(t.title, header = true)
            else Row(t.title, indent = t.level) {
                startActivity(Intent(this, MarkdownActivity::class.java)
                    .putExtra("path", File(base, t.path).path).putExtra("course", id)
                    .putExtra("base", base.path).putExtra("title", t.title))
            }
        }
    }
}
