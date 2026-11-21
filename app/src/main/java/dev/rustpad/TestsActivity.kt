package dev.rustpad

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import kotlin.concurrent.thread

/** واجهة الاختبارات: قائمة الاختبارات + نتيجة كل منها + تشغيل الكل/الفاشل/اختبار واحد. */
class TestsActivity : AppCompatActivity() {
    private lateinit var env: Env
    private lateinit var settings: Settings
    private lateinit var project: File
    private lateinit var list: ListView
    private lateinit var summary: TextView
    private lateinit var filter: EditText
    private lateinit var stopBtn: Button
    private val ui = Handler(Looper.getMainLooper())
    private var names: List<String> = emptyList()
    private val status = HashMap<String, String>()
    private val details = HashMap<String, String>()
    private var lastLog = ""
    @Volatile private var proc: Process? = null
    private var busy = false
    private var shown: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Colors.load(this)
        env = Env(this); settings = Settings(this)
        CrashLogger.install(env)
        project = File(intent.getStringExtra("project") ?: run { finish(); return })
        title = "Tests - ${project.name}"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Colors.BG); setPadding(dp(12), dp(8), dp(12), dp(8)) }
        summary = TextView(this).apply { setTextColor(Colors.TEXT); textSize = 14f; setPadding(0, dp(4), 0, dp(6)); text = "Tap Refresh to list tests" }
        root.addView(summary)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; textSize = 12f; isAllCaps = false; setOnClickListener { f() } }
        bar.addView(btn("Refresh") { discover() }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(btn("Run all") { run(null) }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(btn("Run failed") { runFailed() }, LinearLayout.LayoutParams(0, -2, 1f))
        stopBtn = btn("Stop") { proc?.destroy() }.apply { visibility = View.GONE }
        bar.addView(stopBtn, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bar)
        filter = EditText(this).apply {
            hint = "Filter"; setTextColor(Colors.TEXT); setHintTextColor(Colors.DIM); isSingleLine = true
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { refresh() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        root.addView(filter)
        list = ListView(this).apply { setBackgroundColor(Colors.BG); divider = null }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        list.setOnItemClickListener { _, _, i, _ -> rowMenu(shown[i]) }
        refresh()
        discover()
    }

    override fun onDestroy() { proc?.destroy(); super.onDestroy() }

    private fun refresh() {
        val q = filter.text.toString().trim()
        shown = names.filter { q.isEmpty() || it.contains(q, true) }
        val ok = status.values.count { it == "ok" }; val bad = status.values.count { it == "fail" }
        val ign = status.values.count { it == "ignored" }
        if (names.isNotEmpty() && !busy) summary.text = "${names.size} tests   ✓ $ok   ✗ $bad   ○ $ign   · ${names.size - status.size} not run"
        summary.setTextColor(if (bad > 0) Colors.ERROR else Colors.TEXT)
        list.adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(i: Int) = shown[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, v: View?, p: ViewGroup?): View {
                val n = shown[i]; val s = status[n]
                return TextView(this@TestsActivity).apply {
                    text = (when (s) { "ok" -> "✓  "; "fail" -> "✗  "; "ignored" -> "○  "; "run" -> "…  "; else -> "·  " }) + n
                    typeface = Typeface.MONOSPACE; textSize = 13f
                    setTextColor(when (s) { "fail" -> Colors.ERROR; "ok" -> Colors.TEXT; "run" -> Colors.ACCENT; else -> Colors.DIM })
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                }
            }
        }
    }

    private fun setBusy(b: Boolean, msg: String = "") {
        busy = b
        stopBtn.visibility = if (b) View.VISIBLE else View.GONE
        if (b) summary.text = msg
        if (b) KeepAliveService.start(this, msg) else KeepAliveService.stop(this)
    }

    private fun cargo(args: List<String>, trailing: List<String>, onDone: (Int, String) -> Unit) {
        if (busy) { Toast.makeText(this, "Already running", Toast.LENGTH_SHORT).show(); return }
        if (!env.isInstalled("base")) { Toast.makeText(this, "Toolchain not installed (Terminal → Bootstrap)", Toast.LENGTH_LONG).show(); return }
        CargoConfig.sync(env, settings)
        val extra = CargoConfig.buildEnv(env, settings)
        val jobs = BuildTuner.jobs(this)
        thread(name = "tests") {
            val sb = StringBuilder()
            val code = try {
                CargoRunner(env).run(project, args, jobs, extra, { sb.append(it) }, { }, { proc = it }, trailing)
            } catch (t: Throwable) { sb.append("error: $t\n"); -1 }
            proc = null
            ui.post { setBusy(false); onDone(code, sb.toString()) }
        }
    }

    private fun discover() {
        setBusy(true, "Building tests and listing...")
        cargo(listOf("test"), listOf("--", "--list")) { code, out ->
            lastLog = out
            names = TestParser.parseList(out)
            if (code != 0 && names.isEmpty()) {
                summary.text = "Build failed - tap to see the log"
                summary.setOnClickListener { showLog() }
            } else summary.setOnClickListener(null)
            status.keys.retainAll(names.toSet())
            refresh()
            if (names.isEmpty() && code == 0) summary.text = "No tests found. Add #[test] functions."
        }
    }

    private fun run(name: String?) {
        val targets = if (name == null) names else listOf(name)
        targets.forEach { status[it] = "run" }; refresh()
        setBusy(true, if (name == null) "Running all tests..." else "Running $name...")
        val tr = if (name == null) listOf("--") else listOf("--", "--exact", name, "--nocapture")
        val args = listOf("test")
        cargo(args, tr) { code, out -> finish(targets, code, out) }
    }

    private fun runFailed() {
        val f = status.filter { it.value == "fail" }.keys.toList()
        if (f.isEmpty()) { Toast.makeText(this, "No failed tests", Toast.LENGTH_SHORT).show(); return }
        f.forEach { status[it] = "run" }; refresh()
        setBusy(true, "Re-running ${f.size} failed...")
        cargo(listOf("test"), listOf("--", "--exact") + f) { code, out -> finish(f, code, out) }
    }

    private fun finish(targets: List<String>, code: Int, out: String) {
        lastLog = out
        val r = TestParser.parseRun(out)
        for (t in targets) {
            val s = r.statuses[t]
            if (s != null) status[t] = s else status.remove(t)
        }
        details.putAll(r.details)
        if (r.statuses.isEmpty() && code != 0) {
            summary.text = "Build failed or tests crashed - tap to see the log"
            summary.setOnClickListener { showLog() }
        } else summary.setOnClickListener(null)
        refresh()
    }

    private fun rowMenu(n: String) {
        val opts = ArrayList<Pair<String, () -> Unit>>()
        opts.add("Run this test" to { run(n) })
        if (details[n] != null) opts.add("Show failure output" to { showText(n, details[n]!!) })
        opts.add("Show last log" to { showLog() })
        AlertDialog.Builder(this).setTitle(n)
            .setItems(opts.map { it.first }.toTypedArray()) { _, i -> opts[i].second() }.show()
    }

    private fun showLog() = showText("Last log", lastLog.ifBlank { "(empty)" })

    private fun showText(title: String, text: String) {
        val tv = TextView(this).apply {
            this.text = text; typeface = Typeface.MONOSPACE; textSize = 12f
            setTextIsSelectable(true); setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Close", null).show()
    }
}
