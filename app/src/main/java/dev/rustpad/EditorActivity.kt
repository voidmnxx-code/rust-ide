package dev.rustpad

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.Spanned
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class CodeEditText(ctx: Context) : EditText(ctx) {
    var onCursor: (() -> Unit)? = null
    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onCursor?.invoke()
    }
}

class EditorActivity : AppCompatActivity() {
    private lateinit var env: Env
    private lateinit var project: File
    private lateinit var editor: CodeEditText
    private lateinit var editorScroll: ScrollView
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var panel: ScrollView
    private lateinit var diagList: LinearLayout
    private lateinit var output: TextView

    private var current: File? = null
    private var dirty = false
    private var loading = false
    private var inserting = false
    @Volatile private var version = 0
    private val handler = Handler(Looper.getMainLooper())
    private var hlTask: Runnable? = null
    private val hlExec = Executors.newSingleThreadExecutor()
    @Volatile private var proc: Process? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        env = Env(this)
        CrashLogger.install(env)
        project = File(intent.getStringExtra("project") ?: run { finish(); return })
        buildUi()
        val main = File(project, "src/main.rs")
        if (main.exists()) openFile(main) else openFile(File(project, "Cargo.toml"))
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    override fun onDestroy() {
        proc?.destroy()
        hlExec.shutdown()
        super.onDestroy()
    }

    // ---------- UI ----------
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Colors.BG)
        }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        title = TextView(this).apply {
            setTextColor(Colors.TEXT); textSize = 13f; setPadding(dp(8), 0, dp(8), 0)
            maxLines = 1
        }
        bar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(flatButton("Files") { showFiles() })
        btns.addView(flatButton("Save") { save(); toast("Saved") })
        btns.addView(flatButton("Check") { runCargo("check") })
        btns.addView(flatButton("Build") { runCargo("build") })
        btns.addView(flatButton("Run") { runCargo("run") })
        btns.addView(flatButton("Export APK") { exportApk() })
        btns.addView(flatButton("Stop") { proc?.destroy() })
        btns.addView(flatButton("Output") { panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE })
        btns.addView(flatButton("Term") {
            save()
            startActivity(Intent(this, TerminalActivity::class.java).putExtra("cwd", project.path))
        })
        bar.addView(HorizontalScrollView(this).apply { addView(btns); isHorizontalScrollBarEnabled = false },
            LinearLayout.LayoutParams(0, -2, 2f))
        root.addView(bar)

        editor = CodeEditText(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 14f
            setTextColor(Colors.TEXT)
            setBackgroundColor(Colors.BG)
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setHorizontallyScrolling(false)
            setPadding(dp(8), dp(8), dp(8), dp(200))
            onCursor = { updateStatus() }
        }
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (loading) return
                dirty = true; version++
                if (!inserting && before == 0 && count == 1 && s[start] == '\n') autoIndent(s, start)
                scheduleHighlight()
                updateStatus()
            }
            override fun afterTextChanged(e: Editable?) {}
        })
        editorScroll = ScrollView(this).apply { addView(editor) }
        root.addView(editorScroll, LinearLayout.LayoutParams(-1, 0, 1.2f))

        panel = ScrollView(this).apply {
            setBackgroundColor(Colors.PANEL); visibility = View.GONE
        }
        val pbox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        diagList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        output = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f; setTextColor(Colors.TEXT)
            setTextIsSelectable(true)
        }
        pbox.addView(diagList); pbox.addView(output)
        panel.addView(pbox)
        root.addView(panel, LinearLayout.LayoutParams(-1, 0, 0.8f))

        // صف مفاتيح الرموز
        val keys = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keys.addView(flatButton("Tab") { insert("    ") })
        for (k in listOf("{", "}", "(", ")", "[", "]", ";", ":", "<", ">", "&", "'", "\"", "=", "!", "_", "|", "*", "-", "+", "/", ",", ".", "?", "#")) {
            keys.addView(flatButton(k) { insert(k) })
        }
        root.addView(HorizontalScrollView(this).apply { addView(keys); isHorizontalScrollBarEnabled = false })

        status = TextView(this).apply { setTextColor(Colors.DIM); textSize = 11f; setPadding(dp(8), dp(2), dp(8), dp(2)) }
        root.addView(status)
        setContentView(root)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun updateStatus() {
        val pos = editor.selectionStart.coerceAtLeast(0)
        val t = editor.text
        var line = 1; var lineStart = 0
        for (i in 0 until minOf(pos, t.length)) if (t[i] == '\n') { line++; lineStart = i + 1 }
        status.text = "Ln $line, Col ${pos - lineStart + 1}${if (dirty) "  •  modified" else ""}"
    }

    private fun insert(s: String) {
        val e = editor.editableText
        val a = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
        val b = maxOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
        e.replace(a, b, s)
    }

    // ---------- الملفات ----------
    private fun openFile(f: File) {
        save()
        current = f
        loading = true
        editor.setText(if (f.exists()) f.readText() else "")
        loading = false
        dirty = false; version++
        title.text = "${project.name}/${f.relativeTo(project).path}"
        highlightNow()
        updateStatus()
    }

    private fun save() {
        val f = current ?: return
        if (!dirty) return
        try { f.writeText(editor.text.toString()); dirty = false } catch (e: Exception) { toast("Save failed: ${e.message}") }
    }

    private fun showFiles() {
        val files = project.walkTopDown()
            .onEnter { it.name != "target" && it.name != ".git" }
            .filter { it.isFile }.take(400).map { it.relativeTo(project).path }.sorted().toList()
        val items = (listOf("+ New file...") + files).toTypedArray()
        AlertDialog.Builder(this).setTitle(project.name).setItems(items) { _, i ->
            if (i == 0) newFile() else openFile(File(project, files[i - 1]))
        }.show()
    }

    private fun newFile() {
        val input = EditText(this).apply { hint = "src/utils.rs"; setTextColor(Color.BLACK) }
        AlertDialog.Builder(this).setTitle("New file").setView(input)
            .setPositiveButton("Create") { _, _ ->
                val rel = input.text.toString().trim()
                val f = File(project, rel)
                if (rel.isEmpty() || rel.startsWith("/") || ".." in rel) { toast("Invalid path"); return@setPositiveButton }
                f.parentFile?.mkdirs()
                if (!f.exists()) f.writeText("")
                openFile(f)
            }.setNegativeButton("Cancel", null).show()
    }

    // ---------- التلوين والمسافات البادئة ----------
    private fun scheduleHighlight() {
        hlTask?.let { handler.removeCallbacks(it) }
        val r = Runnable { highlightNow() }
        hlTask = r
        handler.postDelayed(r, 250)
    }

    private fun highlightNow() {
        if (current?.extension != "rs") { clearSpans(); return }
        val text = editor.text.toString()
        val v = version
        hlExec.execute {
            val toks = RustHighlighter.tokens(text)
            runOnUiThread {
                if (v != version || isFinishing) return@runOnUiThread
                val e = editor.editableText
                clearSpans()
                for (t in toks) if (t.end <= e.length) e.setSpan(HlSpan(t.color), t.start, t.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun clearSpans() {
        val e = editor.editableText
        for (s in e.getSpans(0, e.length, HlSpan::class.java)) e.removeSpan(s)
    }

    private fun autoIndent(s: CharSequence, nl: Int) {
        var i = nl - 1
        while (i >= 0 && s[i] != '\n') i--
        val lineStart = i + 1
        var indent = 0
        while (lineStart + indent < nl && s[lineStart + indent] == ' ') indent++
        var j = nl - 1
        while (j >= lineStart && s[j] == ' ') j--
        val extra = if (j >= lineStart && s[j] == '{') 4 else 0
        val pad = " ".repeat(indent + extra)
        if (pad.isEmpty()) return
        editor.post {
            inserting = true
            editor.editableText.insert(nl + 1, pad)
            inserting = false
        }
    }

    // ---------- cargo ----------
    private fun runCargo(sub: String) {
        if (proc != null) { toast("Already running — tap Stop"); return }
        if (!env.isInstalled("base")) { toast("Toolchain not installed (Terminal → Bootstrap)"); return }
        save()
        output.text = ""; diagList.removeAllViews()
        panel.visibility = View.VISIBLE
        KeepAliveService.start(this, "cargo $sub")
        var errors = 0; var warnings = 0
        thread(name = "cargo") {
            val code = try {
                CargoRunner(env).run(project, listOf(sub),
                    onText = { appendOut(it) },
                    onDiag = { d ->
                        if (d.level == "error") errors++ else warnings++
                        runOnUiThread { addDiag(d) }
                    },
                    onProcess = { proc = it })
            } catch (t: Throwable) { appendOut("error: $t\n"); -1 }
            proc = null
            KeepAliveService.stop(this)
            appendOut("[cargo $sub exited with $code — $errors errors, $warnings warnings]\n")
        }
    }

    // ---------- تصدير APK ----------
    private fun exportApk() {
        if (proc != null) { toast("Already running — tap Stop"); return }
        if (!File(project, "app.properties").exists()) {
            toast("Not an Android-app project. Create one with [Android app]."); return
        }
        if (!env.isInstalled("apk-tools")) { toast("apk-tools stage not installed (Terminal → Bootstrap)"); return }
        save()
        AssetInstaller.install(this, env)
        output.text = ""; diagList.removeAllViews()
        panel.visibility = View.VISIBLE
        KeepAliveService.start(this, "Exporting APK")
        thread(name = "export") {
            var apk: String? = null
            val code = try {
                val pb = ProcessBuilder(
                    File(env.prefix, "bin/bash").path, File(env.prefix, "bin/rustpad-export").path, project.path
                ).directory(project).redirectErrorStream(true)
                pb.environment().apply { clear(); putAll(env.environment()); put("CARGO_TERM_COLOR", "never") }
                val p = pb.start()
                proc = p
                p.inputStream.bufferedReader().useLines { lines ->
                    for (l in lines) {
                        appendOut(l + "\n")
                        if (l.startsWith("APK: ")) apk = l.removePrefix("APK: ").trim()
                    }
                }
                p.waitFor()
            } catch (t: Throwable) { appendOut("error: $t\n"); -1 }
            proc = null
            KeepAliveService.stop(this)
            appendOut("[export exited with $code]\n")
            val out = apk
            if (code == 0 && out != null) runOnUiThread { offerInstall(File(out)) }
        }
    }

    private fun offerInstall(apk: File) {
        AlertDialog.Builder(this).setTitle("APK ready")
            .setMessage("${apk.name} (${apk.length() / 1024} KB)\nSigned with the local RustPad key.")
            .setPositiveButton("Install") { _, _ -> installApk(apk) }
            .setNegativeButton("Close", null).show()
    }

    private fun installApk(apk: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fp", apk)
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            toast("Install failed: ${e.message}")
        }
    }

    private fun appendOut(s: String) = runOnUiThread {
        output.append(s)
        if (output.length() > 120_000) output.text = output.text.takeLast(60_000)
        panel.post { panel.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addDiag(d: Diag) {
        val where = if (d.file != null) "  ${d.file}:${d.line}:${d.col}" else ""
        diagList.addView(TextView(this).apply {
            text = "${d.level}: ${d.message}$where"
            textSize = 12f
            setTextColor(if (d.level == "error") Colors.ERROR else Colors.WARN)
            setPadding(0, dp(4), 0, dp(4))
            if (d.file != null) setOnClickListener { goTo(d) }
        })
    }

    private fun goTo(d: Diag) {
        val f = File(project, d.file ?: return)
        if (!f.exists()) return
        if (f != current) openFile(f)
        editor.post {
            val s = editor.text.toString()
            var off = 0; var l = 1
            while (l < d.line) { val n = s.indexOf('\n', off); if (n < 0) break; off = n + 1; l++ }
            val pos = minOf(off + maxOf(d.col - 1, 0), s.length)
            editor.requestFocus()
            editor.setSelection(pos)
            editor.layout?.let { lay ->
                editorScroll.smoothScrollTo(0, maxOf(lay.getLineTop(lay.getLineForOffset(pos)) - dp(60), 0))
            }
        }
    }
}
