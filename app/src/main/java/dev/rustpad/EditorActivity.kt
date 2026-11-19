package dev.rustpad

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.system.Os
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
    /** عرض الهامش بالبكسل (يُرسم داخل الحشو الأيسر). */
    var gutterPx = 0
    /** رقم السطر -> لون علامة (أحمر خطأ / أصفر تحذير). */
    var lineMarks: Map<Int, Int> = emptyMap()

    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE; textAlign = Paint.Align.RIGHT
    }
    private val bgPaint = Paint().apply { color = Color.parseColor("#1A1A1C") }
    private val dim = Color.parseColor("#6B7078")
    private val clip = Rect()

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onCursor?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val lay = layout ?: return
        if (gutterPx <= 0 || !canvas.getClipBounds(clip)) return
        canvas.drawRect(0f, clip.top.toFloat(), gutterPx.toFloat(), clip.bottom.toFloat(), bgPaint)
        numPaint.textSize = textSize * 0.85f
        val top = extendedPaddingTop
        val first = lay.getLineForVertical((clip.top - top).coerceAtLeast(0))
        val last = lay.getLineForVertical((clip.bottom - top).coerceAtLeast(0))
        val t = text
        var num = 1
        val start = lay.getLineStart(first)
        for (k in 0 until minOf(start, t.length)) if (t[k] == '\n') num++
        val x = gutterPx - resources.displayMetrics.density * 6
        for (i in first..last) {
            val ls = lay.getLineStart(i)
            val isStart = ls == 0 || t[ls - 1] == '\n'
            if (i > first && isStart) num++
            if (isStart) {
                numPaint.color = lineMarks[num] ?: dim
                canvas.drawText(num.toString(), x, (lay.getLineBaseline(i) + top).toFloat(), numPaint)
            }
        }
    }
}

class Edit(var start: Int, var removed: String, var added: String)

class Tab(val file: File) {
    val undo = ArrayList<Edit>()
    val redo = ArrayList<Edit>()
    var sel = 0
    var scrollY = 0
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

    private lateinit var settings: Settings
    private lateinit var raList: LinearLayout
    private lateinit var findBar: HorizontalScrollView
    private lateinit var tabBar: LinearLayout
    private val tabs = ArrayList<Tab>()
    private var tab: Tab? = null
    private var undoing = false
    private var pendingRemoved = ""
    private lateinit var findInput: EditText
    private lateinit var replInput: EditText
    private val cargoDiags = ArrayList<Diag>()
    private var ra: RaClient? = null
    private val raDiags = HashMap<String, List<Diag>>()
    private var raState = ""
    private var buildState = ""
    private var raSyncTask: Runnable? = null
    private var autoTask: Runnable? = null
    private var autoRunning = false
    private var completionPopup: android.widget.ListPopupWindow? = null

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
        settings = Settings(this)
        CrashLogger.install(env)
        project = File(intent.getStringExtra("project") ?: run { finish(); return })
        buildUi()
        val main = File(project, "src/main.rs")
        if (main.exists()) openFile(main) else openFile(File(project, "Cargo.toml"))
    }

    override fun onPause() {
        completionPopup?.dismiss()
        save()
        super.onPause()
    }

    override fun onDestroy() {
        proc?.destroy()
        ra?.stop()
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
        btns.addView(flatButton("Find") { toggleFind() })
        btns.addView(flatButton("Git") { showGit() })
        btns.addView(flatButton("Settings") { SettingsDialog.show(this) { onSettingsChanged() } })
        bar.addView(HorizontalScrollView(this).apply { addView(btns); isHorizontalScrollBarEnabled = false },
            LinearLayout.LayoutParams(0, -2, 2f))
        root.addView(bar)

        tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply {
            addView(tabBar); isHorizontalScrollBarEnabled = false; setBackgroundColor(Colors.PANEL)
        })

        findInput = EditText(this).apply {
            hint = "find, or a line number + Go"; textSize = 13f; isSingleLine = true
            setTextColor(Colors.TEXT); setHintTextColor(Colors.DIM)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, _, _ -> findNext(true); true }
        }
        replInput = EditText(this).apply {
            hint = "replace"; textSize = 13f; isSingleLine = true
            setTextColor(Colors.TEXT); setHintTextColor(Colors.DIM)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val fb = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(findInput, LinearLayout.LayoutParams(dp(190), -2))
            addView(replInput, LinearLayout.LayoutParams(dp(130), -2))
            addView(flatButton("Go") { goToLineFromInput() })
            addView(flatButton("◀") { findNext(false) })
            addView(flatButton("▶") { findNext(true) })
            addView(flatButton("Replace") { replaceOne() })
            addView(flatButton("All") { replaceAll() })
            addView(flatButton("✕") { findBar.visibility = View.GONE })
        }
        findBar = HorizontalScrollView(this).apply {
            addView(fb); visibility = View.GONE; setBackgroundColor(Colors.PANEL); isHorizontalScrollBarEnabled = false
        }
        root.addView(findBar)

        editor = CodeEditText(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 14f
            setTextColor(Colors.TEXT)
            setBackgroundColor(Colors.BG)
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setHorizontallyScrolling(false)
            setPadding(dp(52), dp(8), dp(8), dp(200))
            gutterPx = dp(48)
            onCursor = { updateStatus() }
        }
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {
                pendingRemoved = if (!loading && !undoing && s != null && c > 0) s.substring(st, st + c) else ""
            }
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (loading) return
                if (!undoing) recordEdit(start, pendingRemoved, s.substring(start, start + count))
                if (!dirty) { dirty = true; refreshTabs() }
                version++
                if (!inserting && before == 0 && count == 1 && s[start] == '\n') autoIndent(s, start)
                scheduleHighlight()
                updateStatus()
                onEdited(s, start, before, count)
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
            var downX = 0f; var downY = 0f
            setOnTouchListener { v, ev ->
                when (ev.action) {
                    android.view.MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y }
                    android.view.MotionEvent.ACTION_UP -> if (Math.abs(ev.x - downX) < dp(8) && Math.abs(ev.y - downY) < dp(8) &&
                        ev.eventTime - ev.downTime < 300) jumpFromOutput(ev.x, ev.y)
                }
                false
            }
        }
        raList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pbox.addView(raList); pbox.addView(diagList); pbox.addView(output)
        panel.addView(pbox)
        root.addView(panel, LinearLayout.LayoutParams(-1, 0, 0.8f))

        // صف مفاتيح الرموز
        val keys = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keys.addView(flatButton("Undo") { undo() })
        keys.addView(flatButton("Redo") { redo() })
        keys.addView(flatButton("Tab") { insert("    ") })
        keys.addView(flatButton("Complete") { requestCompletion() })
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
        val extra = listOf(if (TreeSitter.available) "hl:TS" else "hl:regex", raState, buildState).filter { it.isNotEmpty() }.joinToString("  |  ")
        status.text = "Ln $line, Col ${pos - lineStart + 1}${if (dirty) "  •  modified" else ""}" +
            if (extra.isNotEmpty()) "   |   $extra" else ""
    }

    private fun insert(s: String) {
        val e = editor.editableText
        val a = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
        val b = maxOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
        e.replace(a, b, s)
    }

    // ---------- تراجع / إعادة ----------
    private fun recordEdit(start: Int, removed: String, added: String) {
        val t = tab ?: return
        t.redo.clear()
        val last = t.undo.lastOrNull()
        if (last != null && removed.isEmpty() && last.removed.isEmpty() && '\n' !in added &&
            start == last.start + last.added.length && last.added.length < 40 && last.added.isNotEmpty() && '\n' !in last.added) {
            last.added += added; return
        }
        if (last != null && added.isEmpty() && last.added.isEmpty() && removed.length == 1 &&
            start + 1 == last.start && last.removed.length < 40) {
            last.start = start; last.removed = removed + last.removed; return
        }
        t.undo.add(Edit(start, removed, added))
        if (t.undo.size > 500) t.undo.removeAt(0)
    }

    private fun undo() {
        val t = tab ?: return
        val e = t.undo.removeLastOrNull() ?: run { toast("Nothing to undo"); return }
        applyEdit(e.start, e.added.length, e.removed)
        t.redo.add(e)
    }

    private fun redo() {
        val t = tab ?: return
        val e = t.redo.removeLastOrNull() ?: run { toast("Nothing to redo"); return }
        applyEdit(e.start, e.removed.length, e.added)
        t.undo.add(e)
    }

    private fun applyEdit(start: Int, len: Int, text: String) {
        val ed = editor.editableText
        val s = start.coerceIn(0, ed.length); val en = (start + len).coerceIn(s, ed.length)
        undoing = true
        try { ed.replace(s, en, text); editor.setSelection((s + text.length).coerceAtMost(ed.length)) }
        finally { undoing = false }
    }

    // ---------- التبويبات ----------
    private fun refreshTabs() {
        tabBar.removeAllViews()
        for (t in tabs.toList()) {
            val active = t === tab
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(if (active) Colors.BG else Colors.PANEL)
                setPadding(dp(10), dp(6), dp(4), dp(6))
            }
            box.addView(TextView(this).apply {
                text = t.file.name + if (active && dirty) " •" else ""
                textSize = 12f; maxLines = 1
                setTextColor(if (active) Colors.TEXT else Colors.DIM)
                setOnClickListener { if (!active) openFile(t.file) }
            })
            if (tabs.size > 1) box.addView(TextView(this).apply {
                text = "  ✕ "; textSize = 12f; setTextColor(Colors.DIM)
                setOnClickListener { closeTab(t) }
            })
            tabBar.addView(box)
        }
        tabBar.visibility = if (tabs.size > 1) View.VISIBLE else View.GONE
    }

    private fun closeTab(t: Tab) {
        val i = tabs.indexOf(t)
        if (i < 0 || tabs.size <= 1) return
        if (t === tab) {
            save()
            tabs.removeAt(i)
            tab = null
            openFile(tabs[minOf(i, tabs.size - 1)].file)
        } else {
            tabs.removeAt(i); refreshTabs()
        }
    }

    // ---------- الملفات ----------
    private fun openFile(f: File, reload: Boolean = false) {
        save()
        tab?.let { it.sel = editor.selectionStart.coerceAtLeast(0); it.scrollY = editorScroll.scrollY }
        val t = tabs.firstOrNull { it.file == f } ?: Tab(f).also { tabs.add(it) }
        if (reload) { t.undo.clear(); t.redo.clear() }
        tab = t
        current = f
        loading = true
        editor.setText(if (f.exists()) f.readText() else "")
        loading = false
        dirty = false; version++
        editor.post {
            editor.setSelection(t.sel.coerceIn(0, editor.text.length))
            editorScroll.scrollTo(0, t.scrollY)
        }
        refreshTabs()
        title.text = "${project.name}/${f.relativeTo(project).path}"
        highlightNow()
        updateStatus()
        refreshRaList()
        refreshMarks()
        handler.postDelayed({ raSync() }, 200)
    }

    private fun save() {
        val f = current ?: return
        if (!dirty) return
        try { f.writeText(editor.text.toString()); dirty = false; refreshTabs() } catch (e: Exception) { toast("Save failed: ${e.message}") }
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
            val toks = RustHighlighter.highlight(text)
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
    // ---------- rust-analyzer (كسول) ----------
    private fun raWanted() = settings.raEnabled && current?.extension == "rs" &&
        File(env.prefix, "bin/rust-analyzer").exists()

    private fun ensureRa(): RaClient? {
        if (!raWanted()) return null
        ra?.let { if (it.alive) return it }
        CargoConfig.sync(env, settings)
        if (!BuildTuner.canBackgroundWork(this)) { raState = "RA: paused (hot/low battery)"; return null }
        val c = RaClient(env, project,
            onDiagnostics = { rel, list -> runOnUiThread { raDiags[rel] = list; refreshRaList() } },
            onState = { st -> runOnUiThread { raState = st; updateStatus() } })
        return try {
            if (c.start()) { ra = c; scheduleIdleCheck(); c } else null
        } catch (t: Throwable) { raState = "RA: failed (${t.message})"; null }
    }

    private fun scheduleIdleCheck() {
        handler.postDelayed({
            val c = ra ?: return@postDelayed
            val idle = android.os.SystemClock.elapsedRealtime() - c.lastUsed > 5 * 60_000
            if (idle || !BuildTuner.canBackgroundWork(this)) {
                c.stop(); ra = null; raState = "RA: stopped (idle)"; updateStatus()
            } else scheduleIdleCheck()
        }, 60_000)
    }

    private fun raSync() {
        val f = current ?: return
        val c = ensureRa() ?: return
        c.sync(f, editor.text.toString())
    }

    private fun onSettingsChanged() {
        if (!settings.raEnabled) { ra?.stop(); ra = null; raDiags.clear(); raState = ""; refreshRaList() }
        else raSync()
        scheduleAutoCheck()
        updateStatus()
    }

    private fun onEdited(s: CharSequence, start: Int, before: Int, count: Int) {
        completionPopup?.dismiss()
        raSyncTask?.let { handler.removeCallbacks(it) }
        val r = Runnable { raSync() }
        raSyncTask = r
        handler.postDelayed(r, 300)
        scheduleAutoCheck()
        if (settings.raEnabled && before == 0 && count == 1) {
            val ch = s[start]
            if (ch == '.' || (ch == ':' && start > 0 && s[start - 1] == ':')) {
                handler.postDelayed({ requestCompletion() }, 60)
            }
        }
    }

    private fun scheduleAutoCheck() {
        autoTask?.let { handler.removeCallbacks(it) }
        if (!settings.autoCheck || current?.extension != "rs") return
        val r = Runnable {
            if (proc == null && BuildTuner.canBackgroundWork(this)) runCargo("check", silent = true)
        }
        autoTask = r
        handler.postDelayed(r, 4000)
    }

    private fun lineCol(pos: Int): Pair<Int, Int> {
        val t = editor.text
        var line = 0; var ls = 0
        for (i in 0 until minOf(pos, t.length)) if (t[i] == '\n') { line++; ls = i + 1 }
        return line to (pos - ls)
    }

    private fun offsetOf(line: Int, col: Int): Int {
        val t = editor.text
        var l = 0; var i = 0
        while (l < line && i < t.length) { if (t[i] == '\n') l++; i++ }
        return minOf(i + col, t.length)
    }

    private fun requestCompletion() {
        val f = current ?: return
        val c = ensureRa()
        if (c == null) { toast("rust-analyzer is off, paused, or not installed (see Settings)"); return }
        if (!c.ready) { toast("rust-analyzer is starting..."); return }
        c.sync(f, editor.text.toString())
        val pos = editor.selectionStart.coerceAtLeast(0)
        val (line, col) = lineCol(pos)
        c.completion(f, line, col) { items -> runOnUiThread { showCompletions(items, pos) } }
    }

    private fun showCompletions(items: List<CItem>, pos: Int) {
        completionPopup?.dismiss()
        if (items.isEmpty() || isFinishing) return
        val lay = editor.layout ?: return
        val cursorY = lay.getLineBottom(lay.getLineForOffset(pos.coerceAtMost(editor.text.length))) +
            editor.paddingTop - editorScroll.scrollY
        val h = (editorScroll.height * 0.4).toInt()
        val labels = items.map { if (it.detail != null) "${it.label}   ${it.detail}" else it.label }
        val popup = android.widget.ListPopupWindow(this)
        popup.anchorView = editorScroll
        popup.setAdapter(object : android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
            override fun getView(p: Int, v: View?, parent: android.view.ViewGroup) =
                (super.getView(p, v, parent) as TextView).apply {
                    setTextColor(Colors.TEXT); textSize = 13f; maxLines = 1; typeface = Typeface.MONOSPACE
                }
        })
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Colors.PANEL))
        popup.width = (editorScroll.width * 0.85).toInt()
        popup.height = h
        popup.isModal = false
        popup.inputMethodMode = android.widget.ListPopupWindow.INPUT_METHOD_NOT_NEEDED
        // إن كان المؤشر في النصف السفلي نعرض القائمة أعلى المحرر، وإلا أسفله
        popup.verticalOffset = if (cursorY > editorScroll.height / 2) -editorScroll.height else -h
        popup.setOnItemClickListener { _, _, i, _ -> applyCompletion(items[i], pos); popup.dismiss() }
        completionPopup = popup
        popup.show()
    }

    private fun applyCompletion(item: CItem, pos: Int) {
        val e = editor.editableText
        val (s, en) = if (item.range != null) {
            offsetOf(item.range[0], item.range[1]) to offsetOf(item.range[2], item.range[3])
        } else {
            var st = pos.coerceAtMost(e.length)
            while (st > 0 && (e[st - 1].isLetterOrDigit() || e[st - 1] == '_')) st--
            st to pos.coerceAtMost(e.length)
        }
        e.replace(minOf(s, en), maxOf(s, en), item.newText)
    }

    private fun refreshRaList() {
        raList.removeAllViews()
        val rel = current?.relativeTo(project)?.path
        val list = (if (rel != null) raDiags[rel] else null).orEmpty()
        for (d in list.take(30)) raList.addView(TextView(this).apply {
            text = "ra ${d.level}: ${d.message}  ${d.line}:${d.col}"
            textSize = 12f
            setTextColor(if (d.level == "error") Colors.ERROR else Colors.WARN)
            setPadding(0, dp(3), 0, dp(3))
            setOnClickListener { goTo(d) }
        })
        val e = list.count { it.level == "error" }; val w = list.size - e
        if (ra != null && ra!!.ready) { raState = "RA: ready ($e err, $w warn)" }
        updateStatus()
        refreshMarks()
    }

    private fun buildEnv(): Map<String, String> {
        CargoConfig.sync(env, settings)
        return CargoConfig.buildEnv(env, settings)
    }

    private fun runCargo(sub: String, silent: Boolean = false) {
        if (proc != null) {
            if (autoRunning && !silent) { proc?.destroy(); handler.postDelayed({ runCargo(sub) }, 600) }
            else if (!silent) toast("Already running — tap Stop")
            return
        }
        if (!env.isInstalled("base")) { if (!silent) toast("Toolchain not installed (Terminal → Bootstrap)"); return }
        save()
        autoRunning = silent
        output.text = ""; diagList.removeAllViews(); cargoDiags.clear(); refreshMarks()
        if (silent) { buildState = "checking..."; updateStatus() }
        else { panel.visibility = View.VISIBLE; KeepAliveService.start(this, "cargo $sub") }
        val jobs = BuildTuner.jobs(this)
        val extraEnv = buildEnv()
        var errors = 0; var warnings = 0
        thread(name = "cargo") {
            val code = try {
                CargoRunner(env).run(project, listOf(sub), jobs, extraEnv,
                    onText = { appendOut(it) },
                    onDiag = { d ->
                        if (d.level == "error") errors++ else warnings++
                        runOnUiThread { addDiag(d) }
                    },
                    onProcess = { proc = it })
            } catch (t: Throwable) { appendOut("error: $t\n"); -1 }
            proc = null
            autoRunning = false
            if (!silent) KeepAliveService.stop(this)
            appendOut("[cargo $sub exited with $code — $errors errors, $warnings warnings, -j$jobs]\n")
            runOnUiThread { buildState = "$sub: $errors err, $warnings warn"; updateStatus() }
        }
    }

    // ---------- بحث / استبدال / انتقال لسطر ----------
    private fun toggleFind() {
        if (findBar.visibility == View.VISIBLE) { findBar.visibility = View.GONE; return }
        findBar.visibility = View.VISIBLE
        val a = editor.selectionStart; val b = editor.selectionEnd
        if (a in 0 until b && b - a < 80) findInput.setText(editor.text.substring(a, b))
        findInput.requestFocus()
    }

    private fun scrollToOffset(pos: Int) {
        editor.post {
            editor.layout?.let { lay ->
                editorScroll.smoothScrollTo(0, maxOf(lay.getLineTop(lay.getLineForOffset(pos.coerceIn(0, editor.text.length))) - dp(60), 0))
            }
        }
    }

    private fun findNext(forward: Boolean) {
        val q = findInput.text.toString()
        if (q.isEmpty()) return
        // ":123" ينتقل إلى السطر 123
        Regex("^:(\\d+)$").find(q)?.let {
            val pos = offsetOf(maxOf(it.groupValues[1].toInt() - 1, 0), 0)
            editor.requestFocus(); editor.setSelection(pos); scrollToOffset(pos); return
        }
        val t = editor.text.toString()
        var idx = if (forward) t.indexOf(q, editor.selectionEnd, true) else t.lastIndexOf(q, editor.selectionStart - 1, true)
        if (idx < 0) idx = if (forward) t.indexOf(q, 0, true) else t.lastIndexOf(q, t.length, true)
        if (idx < 0) {
            toast(if (q.all { it.isDigit() }) "Not found as text — tap Go to jump to line $q" else "Not found")
            return
        }
        editor.requestFocus()
        editor.setSelection(idx, idx + q.length)
        scrollToOffset(idx)
    }

    private fun goToLineFromInput() {
        val n = findInput.text.toString().trim().removePrefix(":").toIntOrNull()
        if (n == null || n < 1) { toast("Type a line number first"); return }
        val total = editor.text.count { it == '\n' } + 1
        val line = minOf(n, total)
        val pos = offsetOf(line - 1, 0)
        editor.requestFocus(); editor.setSelection(pos); scrollToOffset(pos)
        if (n > total) toast("File has only $total lines")
    }

    private fun replaceOne() {
        val q = findInput.text.toString()
        if (q.isEmpty()) return
        val r = replInput.text.toString()
        val a = editor.selectionStart; val b = editor.selectionEnd
        if (a >= 0 && b - a == q.length && editor.text.substring(a, b).equals(q, true)) {
            editor.editableText.replace(a, b, r)
            editor.setSelection(a + r.length)
        }
        findNext(true)
    }

    private fun replaceAll() {
        val q = findInput.text.toString()
        if (q.isEmpty()) return
        val r = replInput.text.toString()
        val t = editor.text.toString()
        var n = 0; var i = t.indexOf(q, 0, true)
        while (i >= 0) { n++; i = t.indexOf(q, i + q.length, true) }
        if (n == 0) { toast("Not found"); return }
        editor.editableText.replace(0, t.length, t.replace(q, r, ignoreCase = true))
        toast("$n replaced")
    }

    // ---------- علامات الأخطاء على الهامش ----------
    private fun refreshMarks() {
        val rel = current?.relativeTo(project)?.path
        val m = HashMap<Int, Int>()
        fun add(d: Diag) {
            if (d.file == rel && d.line > 0) {
                val c = if (d.level == "error") Colors.ERROR else Colors.WARN
                if (m[d.line] != Colors.ERROR) m[d.line] = c
            }
        }
        cargoDiags.forEach { add(it) }
        if (rel != null) raDiags[rel]?.forEach { add(it) }
        editor.lineMarks = m
        editor.invalidate()
    }

    // ---------- Git ----------
    private fun gitBin() = File(env.prefix, "bin/git")

    private fun runGit(vararg steps: List<String>, reloadAfter: Boolean = false) {
        if (proc != null) { toast("Busy — tap Stop"); return }
        if (!gitBin().exists()) { toast("git not installed (Terminal → Bootstrap)"); return }
        save()
        output.text = ""; panel.visibility = View.VISIBLE
        KeepAliveService.start(this, "git")
        thread(name = "git") {
            var code = 0
            try {
                for (s in steps) {
                    appendOut("$ git ${s.joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it }}\n")
                    val pb = ProcessBuilder(listOf(gitBin().path) + s).directory(project).redirectErrorStream(true)
                    pb.environment().apply {
                        clear(); putAll(env.environment())
                        put("GIT_TERMINAL_PROMPT", "0"); put("GIT_EDITOR", "true"); put("GIT_PAGER", "cat")
                    }
                    val p = pb.start()
                    proc = p
                    p.inputStream.bufferedReader().forEachLine { appendOut(it + "\n") }
                    code = p.waitFor()
                    if (code != 0) break
                }
            } catch (t: Throwable) { appendOut("error: $t\n"); code = -1 }
            proc = null
            KeepAliveService.stop(this)
            appendOut("[git exited with $code]\n")
            if (code == 0 && reloadAfter) runOnUiThread { current?.let { openFile(it, reload = true) } }
        }
    }

    private fun promptText(title: String, hint: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply { this.hint = hint; setTextColor(Color.BLACK) }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("OK") { _, _ -> input.text.toString().trim().let { if (it.isNotEmpty()) onOk(it) } }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showGit() {
        val items = arrayOf(
            "Status", "Commit all...", "Log (last 20)", "Diff (stat)", "Pull (rebase)", "Push",
            "Init repository", "Set remote (origin)...", "Identity & GitHub token...",
        )
        AlertDialog.Builder(this).setTitle("Git — ${project.name}").setItems(items) { _, i ->
            when (i) {
                0 -> runGit(listOf("status", "-sb"))
                1 -> promptText("Commit message", "message") { msg -> runGit(listOf("add", "-A"), listOf("commit", "-m", msg)) }
                2 -> runGit(listOf("log", "--oneline", "-n", "20"))
                3 -> runGit(listOf("diff", "--stat"))
                4 -> runGit(listOf("pull", "--rebase"), reloadAfter = true)
                5 -> runGit(listOf("push", "-u", "origin", "HEAD"))
                6 -> {
                    val gi = File(project, ".gitignore")
                    if (!gi.exists()) gi.writeText("target/\ndist/\n")
                    runGit(listOf("init", "-b", "main"))
                }
                7 -> promptText("Remote URL", "https://github.com/user/repo.git") { url ->
                    runGit(listOf("config", "remote.origin.url", url),
                        listOf("config", "remote.origin.fetch", "+refs/heads/*:refs/remotes/origin/*"))
                }
                8 -> identityDialog()
            }
        }.show()
    }

    private fun identityDialog() {
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        fun field(h: String, secret: Boolean = false) = EditText(this).apply {
            hint = h; setTextColor(Color.BLACK); isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or
                if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }.also { lay.addView(it) }
        val name = field("Name (commit author)")
        val email = field("Email")
        val user = field("GitHub username")
        val token = field("Personal access token", secret = true)
        AlertDialog.Builder(this).setTitle("Git identity")
            .setMessage("The token is stored as plain text in the app's private folder (~/.git-credentials), like Termux.")
            .setView(lay)
            .setPositiveButton("Save") { _, _ ->
                val steps = ArrayList<List<String>>()
                if (name.text.isNotBlank()) steps.add(listOf("config", "--global", "user.name", name.text.toString().trim()))
                if (email.text.isNotBlank()) steps.add(listOf("config", "--global", "user.email", email.text.toString().trim()))
                if (user.text.isNotBlank() && token.text.isNotBlank()) {
                    try {
                        val f = File(env.home, ".git-credentials")
                        f.writeText("https://${Uri.encode(user.text.toString().trim())}:${Uri.encode(token.text.toString().trim())}@github.com\n")
                        Os.chmod(f.path, 0x180) // 0600
                        steps.add(listOf("config", "--global", "credential.helper", "store"))
                    } catch (e: Exception) { toast("Could not save token: ${e.message}") }
                }
                if (steps.isNotEmpty()) runGit(*steps.toTypedArray()) else toast("Nothing to save")
            }.setNegativeButton("Cancel", null).show()
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
        output.text = ""; diagList.removeAllViews(); cargoDiags.clear(); refreshMarks()
        panel.visibility = View.VISIBLE
        KeepAliveService.start(this, "Exporting APK")
        thread(name = "export") {
            var apk: String? = null
            val code = try {
                val pb = ProcessBuilder(
                    File(env.prefix, "bin/bash").path, File(env.prefix, "bin/rustpad-export").path, project.path
                ).directory(project).redirectErrorStream(true)
                pb.environment().apply {
                    clear(); putAll(env.environment()); put("CARGO_TERM_COLOR", "never")
                    put("RUSTPAD_JOBS", BuildTuner.jobs(this@EditorActivity).toString()); putAll(buildEnv())
                }
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
        cargoDiags.add(d); refreshMarks()
        val where = if (d.file != null) "  ${d.file}:${d.line}:${d.col}" else ""
        diagList.addView(TextView(this).apply {
            text = "${d.level}: ${d.message}$where"
            textSize = 12f
            setTextColor(if (d.level == "error") Colors.ERROR else Colors.WARN)
            setPadding(0, dp(4), 0, dp(4))
            if (d.file != null) setOnClickListener { goTo(d) }
        })
    }

    /** نقرة على سطر في الإخراج يحوي مثل src/main.rs:12:5 تفتح الملف عند ذلك السطر. */
    private fun jumpFromOutput(x: Float, y: Float) {
        val txt = output.text ?: return
        if (txt.isEmpty()) return
        val off = output.getOffsetForPosition(x, y).coerceIn(0, txt.length - 1)
        val ls = (if (off > 0) txt.lastIndexOf('\n', off - 1) else -1).let { if (it < 0) 0 else it + 1 }
        val le = txt.indexOf('\n', off).let { if (it < 0) txt.length else it }
        if (le <= ls) return
        val line = txt.substring(ls, le)
        val m = Regex("([\\w./-]+\\.(?:rs|toml)):(\\d+)(?::(\\d+))?").find(line) ?: return
        val path = m.groupValues[1].removePrefix("./")
        var f = File(project, path)
        if (!f.exists()) f = File(path)
        if (!f.exists() || !f.canonicalPath.startsWith(project.canonicalPath)) return
        val rel = if (f.isAbsolute) f.relativeTo(project).path else path
        goTo(Diag("note", "", rel, m.groupValues[2].toInt(), m.groupValues[3].toIntOrNull() ?: 1))
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
