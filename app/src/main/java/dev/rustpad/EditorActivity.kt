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
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** يحلّ محل كتلة مطويّة (حرف واحد U+FFFC) ويحتفظ بالنص المخفي. */
class FoldSpan(val hidden: String) : android.text.style.ReplacementSpan() {
    val hiddenLines = hidden.count { it == '\n' }
    private val label = " \u22EF "
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#33383F") }
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
        paint.measureText(label).toInt() + 8

    override fun draw(c: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val w = paint.measureText(label)
        c.drawRoundRect(x + 2, top + 3f, x + w + 6, bottom - 2f, 8f, 8f, bg)
        val old = paint.color
        paint.color = Color.parseColor("#9AA3AE")
        c.drawText(label, x + 4, y.toFloat(), paint)
        paint.color = old
    }
}

class CodeEditText(ctx: Context) : EditText(ctx) {
    /** بدايات الأسطر القابلة للطي (في النص الظاهر). */
    var foldStarts: Set<Int> = emptySet()
    var onGutterTap: ((Int) -> Unit)? = null
    var onFoldSpanTap: ((FoldSpan) -> Unit)? = null
    var cutting = false
    var onZoom: ((Float) -> Unit)? = null
    private val scaleDetector = android.view.ScaleGestureDetector(ctx, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: android.view.ScaleGestureDetector): Boolean { onZoom?.invoke(d.scaleFactor); return true }
    })
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L

    /** النص الكامل لمجال [s,e) مع إرجاع المخفي. */
    fun fullTextOf(s: Int, e: Int): String {
        val ed = editableText
        val sb = StringBuilder()
        var p = s
        for (sp in ed.getSpans(s, e, FoldSpan::class.java).sortedBy { ed.getSpanStart(it) }) {
            val a = ed.getSpanStart(sp)
            if (a < p) continue
            sb.append(ed, p, a).append(sp.hidden)
            p = ed.getSpanEnd(sp)
        }
        sb.append(ed, p, e)
        return sb.toString()
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id == android.R.id.copy || id == android.R.id.cut) {
            val a = minOf(selectionStart, selectionEnd); val b = maxOf(selectionStart, selectionEnd)
            if (a >= 0 && b > a && editableText.getSpans(a, b, FoldSpan::class.java).isNotEmpty()) {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("code", fullTextOf(a, b)))
                if (id == android.R.id.cut) { cutting = true; try { editableText.delete(a, b) } finally { cutting = false } }
                return true
            }
        }
        return super.onTextContextMenuItem(id)
    }

    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        if (scaleDetector.isInProgress) return true
        val slop = context.dp(8)
        when (ev.action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; downT = ev.eventTime
                if (gutterPx > 0 && ev.x < gutterPx) { super.onTouchEvent(ev); return true }
            }
            android.view.MotionEvent.ACTION_UP -> {
                val tap = Math.abs(ev.x - downX) < slop && Math.abs(ev.y - downY) < slop && ev.eventTime - downT < 300
                val lay = layout
                if (tap && lay != null) {
                    if (downX < gutterPx) {
                        var ls = lay.getLineStart(lay.getLineForVertical((ev.y - extendedPaddingTop).toInt().coerceAtLeast(0)))
                        val t = text
                        while (ls > 0 && ls <= t.length && t[ls - 1] != '\n') ls--
                        onGutterTap?.invoke(ls)
                        return true
                    }
                    val off = getOffsetForPosition(ev.x, ev.y)
                    val sp = editableText.getSpans((off - 1).coerceAtLeast(0), (off + 1).coerceAtMost(editableText.length), FoldSpan::class.java).firstOrNull()
                    if (sp != null) { onFoldSpanTap?.invoke(sp); return true }
                }
            }
        }
        return super.onTouchEvent(ev)
    }

    var onCursor: (() -> Unit)? = null
    /** عرض الهامش بالبكسل (يُرسم داخل الحشو الأيسر). */
    var gutterPx = 0
    /** رقم السطر -> لون علامة (أحمر خطأ / أصفر تحذير). */
    var lineMarks: Map<Int, Int> = emptyMap()

    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE; textAlign = Paint.Align.RIGHT
    }
    private val bgPaint = Paint().apply { color = Colors.PANEL }
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
        val ed = editableText
        var num = 1
        val start = lay.getLineStart(first)
        for (k in 0 until minOf(start, t.length)) if (t[k] == '\n') num++
        if (start > 0) for (sp in ed.getSpans(0, start, FoldSpan::class.java)) num += sp.hiddenLines
        val x = gutterPx - resources.displayMetrics.density * 6
        for (i in first..last) {
            val ls = lay.getLineStart(i)
            val isStart = ls == 0 || t[ls - 1] == '\n'
            if (i > first) {
                for (sp in ed.getSpans(lay.getLineStart(i - 1), ls, FoldSpan::class.java)) num += sp.hiddenLines
                if (isStart) num++
            }
            if (isStart) {
                val le = lay.getLineEnd(i)
                val folded = ed.getSpans(ls, le, FoldSpan::class.java)
                var col = lineMarks[num]
                for (sp in folded) for (h in 1..sp.hiddenLines) {
                    val m = lineMarks[num + h]
                    if (m != null && (col == null || m == Colors.ERROR)) col = m
                }
                numPaint.color = col ?: dim
                canvas.drawText(num.toString(), x, (lay.getLineBaseline(i) + top).toFloat(), numPaint)
                if (folded.isNotEmpty() || ls in foldStarts) {
                    val old = numPaint.textSize
                    numPaint.textSize = textSize * 1.35f
                    numPaint.color = if (folded.isNotEmpty()) Colors.ACCENT else Color.parseColor("#A0A6AF")
                    numPaint.textAlign = Paint.Align.LEFT
                    canvas.drawText(if (folded.isNotEmpty()) "\u25B6" else "\u25BC", resources.displayMetrics.density * 2, (lay.getLineBaseline(i) + top).toFloat(), numPaint)
                    numPaint.textAlign = Paint.Align.RIGHT
                    numPaint.textSize = old
                }
            }
        }
    }
}

/** ScrollView يخفي/يُظهر مقبض السحب المرافق له مع نفسه. */
class PanelScroll(ctx: Context) : ScrollView(ctx) {
    var companion: View? = null
    override fun setVisibility(v: Int) { super.setVisibility(v); companion?.visibility = v }
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
    private lateinit var panel: PanelScroll
    private lateinit var panelHandle: View
    private var treeBox: LinearLayout? = null
    private var treePanel: View? = null
    private val collapsed = HashSet<String>()
    private val isTablet get() = resources.configuration.screenWidthDp >= 720
    private lateinit var diagList: LinearLayout
    private lateinit var output: TextView

    private lateinit var settings: Settings
    private lateinit var raList: LinearLayout
    private lateinit var findBar: HorizontalScrollView
    private lateinit var tabBar: LinearLayout
    private val tabs = ArrayList<Tab>()
    private var tab: Tab? = null
    private var undoing = false
    private var folding = false
    private var restoreFold: Pair<FoldSpan, Int>? = null
    @Volatile private var foldMap: Map<Int, IntArray> = emptyMap()
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
    private var exCourse: String? = null
    private lateinit var fab: TextView
    private var exId = ""

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
        Colors.load(this)
        env = Env(this)
        settings = Settings(this)
        CrashLogger.install(env)
        project = File(intent.getStringExtra("project") ?: run { finish(); return })
        exCourse = intent.getStringExtra("ex_course")
        exId = intent.getStringExtra("ex_id") ?: ""
        buildUi()
        val main = File(project, "src/main.rs")
        val lib = File(project, "src/lib.rs")
        val first = intent.getStringExtra("file")?.let { File(it) }
        when {
            first != null && first.exists() -> openFile(first)
            main.exists() -> openFile(main)
            lib.exists() -> openFile(lib)
            else -> openFile(File(project, "Cargo.toml"))
        }
    }

    override fun onPause() {
        completionPopup?.dismiss()
        save()
        super.onPause()
    }

    private var lastBackup = 0L

    override fun onStop() {
        super.onStop()
        if (!settings.autoBackup) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastBackup != 0L && now - lastBackup < 5 * 60_000) return
        lastBackup = now
        val ext = PermissionManager(this).hasStorageAccess()
        thread(name = "backup") {
            try {
                val bm = BackupManager(env)
                if (bm.needsBackup(project)) bm.backup(project, ext)
            } catch (_: Throwable) { }
        }
    }

    /** ضغط ذاكرة: rust-analyzer أثقل عملية، نوقفه أولاً. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && ra != null) {
            ra?.stop(); ra = null
            raState = "RA: stopped (low memory)"
            updateStatus()
        }
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

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Colors.PANEL)
        }
        bar.addView(iconButton("\u2630", "Files") { if (isTablet) treePanel?.let { it.visibility = if (it.visibility == View.VISIBLE) View.GONE else View.VISIBLE } else showFiles() })
        title = TextView(this).apply {
            setTextColor(Colors.TEXT); textSize = 14f; setPadding(dp(4), 0, dp(8), 0)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        bar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(iconButton("\u2713", "Check") { runCargo("check") })
        bar.addView(iconButton("\u2315", "Find") { toggleFind() })
        lateinit var moreBtn: View
        moreBtn = iconButton("\u22EF", "More") { showMore(moreBtn) }
        bar.addView(moreBtn)
        root.addView(bar)

        // إجراءات سياقية للتمارين
        if (exCourse != null) {
            val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(4), dp(8), dp(4)) }
            fun addChip(l: String, f: () -> Unit) =
                chips.addView(chip(l, f), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
            when (exCourse) {
                "rustlings" -> { addChip("Hint") { showHint() }; addChip("Solution") { showSolution() }
                    addChip("Reset") { resetExercise() }; addChip("Next \u25B6") { nextExercise() } }
                "exercism" -> { addChip("Instructions") { showInstructions() }; addChip("Test") { runCargo("test") }
                    addChip("Example") { showExample() } }
            }
            root.addView(HorizontalScrollView(this).apply {
                addView(chips); isHorizontalScrollBarEnabled = false; setBackgroundColor(Colors.BG)
            })
        }

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
            typeface = Typeface.MONOSPACE; textSize = settings.fontSp
            setTextColor(Colors.TEXT)
            setBackgroundColor(Colors.BG)
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setHorizontallyScrolling(false)
            setPadding(dp(60), dp(8), dp(8), dp(200))
            gutterPx = dp(56)
            onCursor = { updateStatus() }
            onGutterTap = { ls -> toggleFoldAt(ls) }
            onZoom = { f ->
                val n = (settings.fontSp * f).coerceIn(9f, 30f)
                if (Math.abs(n - settings.fontSp) >= 0.15f) { settings.fontSp = n; setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, n) }
            }
            onFoldSpanTap = { sp -> unfold(sp) }
        }
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {
                pendingRemoved = if (!loading && !undoing && !folding && s != null && c > 0) s.substring(st, st + c) else ""
                restoreFold = null
                if (!loading && !folding && !undoing && !editor.cutting && c == 1 && a == 0 && s is Spanned) {
                    s.getSpans(st, st + 1, FoldSpan::class.java).firstOrNull()?.let { restoreFold = it to st }
                }
            }
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (loading) return
                if (folding) { version++; return }
                if (!undoing) recordEdit(start, pendingRemoved, s.substring(start, start + count))
                if (!dirty) { dirty = true; refreshTabs() }
                version++
                if (!inserting && before == 0 && count == 1 && s[start] == '\n') autoIndent(s, start)
                scheduleHighlight()
                updateStatus()
                onEdited(s, start, before, count)
            }
            override fun afterTextChanged(e: Editable?) {
                val r = restoreFold ?: return
                restoreFold = null
                // حذف علامة الطي بمفرده = فتحها (لا نفقد الكود المخفي)
                editor.post { insertHidden(r.first, r.second) }
            }
        })
        editorScroll = ScrollView(this).apply { addView(editor) }
        root.addView(editorScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        panel = PanelScroll(this).apply {
            setBackgroundColor(Colors.PANEL); visibility = View.GONE
        }
        panelHandle = TextView(this).apply {
            text = "\u2550\u2550\u2550"; gravity = Gravity.CENTER; setTextColor(Colors.DIM); textSize = 12f
            setBackgroundColor(Colors.PANEL); visibility = View.GONE
            var y0 = 0f; var h0 = 0
            setOnTouchListener { _, ev ->
                when (ev.action) {
                    android.view.MotionEvent.ACTION_DOWN -> { y0 = ev.rawY; h0 = panel.layoutParams.height }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val nh = (h0 + (y0 - ev.rawY)).toInt().coerceIn(dp(80), (resources.displayMetrics.heightPixels * 0.7).toInt())
                        panel.layoutParams = panel.layoutParams.apply { height = nh }
                    }
                }
                true
            }
        }
        panel.companion = panelHandle
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
        root.addView(panelHandle, LinearLayout.LayoutParams(-1, dp(24)))
        root.addView(panel, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.32).toInt()))

        // صف مفاتيح الرموز
        val keys = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keys.addView(keyButton("Fold") { foldAtCursor() })
        keys.addView(keyButton("Unfold all") { unfoldAll() })
        keys.addView(keyButton("Undo") { undo() })
        keys.addView(keyButton("Redo") { redo() })
        keys.addView(keyButton("Tab") { insert("    ") })
        keys.addView(keyButton("Complete") { requestCompletion() })
        keys.addView(keyButton("Def") { goDefinition() })
        keys.addView(keyButton("Info") { showHover() })
        for (k in listOf("{", "}", "(", ")", "[", "]", ";", ":", "<", ">", "&", "'", "\"", "=", "!", "_", "|", "*", "-", "+", "/", ",", ".", "?", "#")) {
            keys.addView(keyButton(k) { insert(k) })
        }
        root.addView(HorizontalScrollView(this).apply { addView(keys); isHorizontalScrollBarEnabled = false })

        status = TextView(this).apply { setTextColor(Colors.DIM); textSize = 11f; setPadding(dp(8), dp(2), dp(8), dp(2)) }
        root.addView(status)
        val frame = android.widget.FrameLayout(this)
        if (isTablet) {
            val outer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Colors.BG) }
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(24)) }
            treeBox = box
            treePanel = ScrollView(this).apply { setBackgroundColor(Colors.PANEL); addView(box) }
            outer.addView(treePanel, LinearLayout.LayoutParams(dp(250), -1))
            outer.addView(root, LinearLayout.LayoutParams(0, -1, 1f))
            frame.addView(outer, android.widget.FrameLayout.LayoutParams(-1, -1))
        } else frame.addView(root, android.widget.FrameLayout.LayoutParams(-1, -1))
        fab = TextView(this).apply {
            text = "\u25B6"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = roundBg(Colors.ACCENT, 28)
            elevation = dp(6).toFloat()
            contentDescription = "Run"
            setOnClickListener { fabAction() }
        }
        frame.addView(fab, android.widget.FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(16); bottomMargin = dp(96)
        })
        setContentView(frame)
        handler.post(object : Runnable {
            override fun run() {
                if (isFinishing) return
                fab.text = if (proc != null) "\u25A0" else "\u25B6"
                handler.postDelayed(this, 400)
            }
        })
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun updateStatus() {
        val pos = editor.selectionStart.coerceAtLeast(0)
        val t = editor.text
        var line = 1; var lineStart = 0
        for (i in 0 until minOf(pos, t.length)) if (t[i] == '\n') { line++; lineStart = i + 1 }
        if (pos > 0) for (sp in editor.editableText.getSpans(0, minOf(pos, t.length), FoldSpan::class.java)) line += sp.hiddenLines
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

    private fun hasFolds() = editor.editableText.getSpans(0, editor.text.length, FoldSpan::class.java).isNotEmpty()

    private fun undo() {
        if (hasFolds()) { toast("Unfold all before Undo"); return }
        val t = tab ?: return
        val e = t.undo.removeLastOrNull() ?: run { toast("Nothing to undo"); return }
        applyEdit(e.start, e.added.length, e.removed)
        t.redo.add(e)
    }

    private fun redo() {
        if (hasFolds()) { toast("Unfold all before Redo"); return }
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

    // ---------- طيّ الكود ----------
    private fun clearUndoStacks() { tab?.let { it.undo.clear(); it.redo.clear() } }

    private fun foldAtCursor() {
        val t = editor.text
        val pos = editor.selectionStart.coerceIn(0, t.length)
        var ls = pos
        while (ls > 0 && t[ls - 1] != '\n') ls--
        if (foldMap[ls] == null || !toggleFoldAt(ls)) {
            // ابحث عن أقرب كتلة تحتوي المؤشر
            val best = foldMap.values.filter { it[0] < pos && pos <= it[1] }.maxByOrNull { it[0] }
            if (best == null) { toast("No block here"); return }
            foldRange(best[0] + 1, best[1])
        }
    }

    private fun toggleFoldAt(ls: Int): Boolean {
        val t = editor.text
        val le = t.indexOf('\n', ls).let { if (it < 0) t.length else it }
        val sp = editor.editableText.getSpans(ls, le, FoldSpan::class.java).firstOrNull()
        if (sp != null) { unfold(sp); return true }
        val r = foldMap[ls] ?: return false
        if (r[1] >= t.length || t[r[0]] != '{' || t[r[1]] != '}') return false
        foldRange(r[0] + 1, r[1])
        return true
    }

    private fun foldRange(s: Int, e: Int) {
        if (e <= s) return
        val ed = editor.editableText
        val hidden = editor.fullTextOf(s, e)
        folding = true
        try {
            ed.replace(s, e, "\uFFFC")
            ed.setSpan(FoldSpan(hidden), s, s + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            editor.setSelection(s + 1)
        } finally { folding = false }
        clearUndoStacks()
        scheduleHighlight()
    }

    private fun unfold(sp: FoldSpan) {
        val ed = editor.editableText
        val s = ed.getSpanStart(sp)
        if (s < 0) return
        folding = true
        try { ed.removeSpan(sp); ed.replace(s, s + 1, sp.hidden) } finally { folding = false }
        clearUndoStacks()
        scheduleHighlight()
    }

    private fun insertHidden(sp: FoldSpan, pos: Int) {
        val ed = editor.editableText
        folding = true
        try { ed.insert(pos.coerceIn(0, ed.length), sp.hidden) } finally { folding = false }
        clearUndoStacks()
        scheduleHighlight()
    }

    private fun unfoldAll() {
        val ed = editor.editableText
        val spans = ed.getSpans(0, ed.length, FoldSpan::class.java).sortedByDescending { ed.getSpanStart(it) }
        if (spans.isEmpty()) return
        val sel = editor.selectionStart
        var delta = 0
        folding = true
        try {
            for (sp in spans) {
                val s = ed.getSpanStart(sp)
                if (s < 0) continue
                if (s < sel) delta += sp.hidden.length - 1
                ed.removeSpan(sp); ed.replace(s, s + 1, sp.hidden)
            }
            editor.setSelection((sel + delta).coerceIn(0, ed.length))
        } finally { folding = false }
        clearUndoStacks()
        scheduleHighlight()
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
        refreshTree()
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
        try { f.writeText(editor.fullTextOf(0, editor.text.length)); dirty = false; refreshTabs() } catch (e: Exception) { toast("Save failed: ${e.message}") }
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
        if (current?.extension != "rs") { clearSpans(); foldMap = emptyMap(); editor.foldStarts = emptySet(); return }
        val text = editor.text.toString()
        val v = version
        hlExec.execute {
            val toks = RustHighlighter.highlight(text)
            val fm = try { FoldScanner.scan(text) } catch (_: Throwable) { emptyMap() }
            runOnUiThread {
                if (v != version || isFinishing) return@runOnUiThread
                foldMap = fm
                editor.foldStarts = fm.keys
                editor.invalidate()
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

    // ---------- rust-analyzer: تنقّل وتحرير ----------
    private fun raAt(block: (RaClient, File, Int, Int) -> Unit) {
        val f = current ?: return
        if (f.extension != "rs") { toast("Rust files only"); return }
        unfoldAll()
        val c = ensureRa()
        if (c == null) { toast("rust-analyzer is off, paused, or not installed (see Settings)"); return }
        if (!c.ready) { toast("rust-analyzer is starting..."); return }
        c.sync(f, editor.text.toString())
        val (line, col) = lineCol(editor.selectionStart.coerceAtLeast(0))
        block(c, f, line, col)
    }

    private fun goDefinition() = raAt { c, f, l, col ->
        c.definition(f, l, col) { locs -> runOnUiThread {
            when (locs.size) { 0 -> toast("No definition found"); 1 -> gotoLoc(locs[0]); else -> pickLoc("Definitions", locs) }
        } }
    }

    private fun showHover() = raAt { c, f, l, col ->
        c.hover(f, l, col) { txt -> runOnUiThread { if (txt == null) toast("No info here") else showText("Info", txt) } }
    }

    private fun findReferences() = raAt { c, f, l, col ->
        c.references(f, l, col) { locs -> runOnUiThread {
            if (locs.isEmpty()) toast("No references") else pickLoc("References (${locs.size})", locs)
        } }
    }

    private fun renameSymbol() = raAt { c, f, l, col ->
        val t = editor.text
        var s = editor.selectionStart.coerceIn(0, t.length); var e = s
        while (s > 0 && (t[s - 1].isLetterOrDigit() || t[s - 1] == '_')) s--
        while (e < t.length && (t[e].isLetterOrDigit() || t[e] == '_')) e++
        val input = EditText(this).apply { setText(t.substring(s, e)); setSelection(0, text.length); setSingleLine() }
        AlertDialog.Builder(this).setTitle("Rename symbol").setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                c.rename(f, l, col, name) { we, err -> runOnUiThread {
                    if (we == null) toast(err ?: "Cannot rename here") else applyWorkspaceEdit(we)
                } }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun codeActions() = raAt { c, f, l, col ->
        val (l2, c2) = lineCol(maxOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0))
        val (l1, c1) = lineCol(minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0))
        c.codeActions(f, l1, c1, l2, c2) { acts -> runOnUiThread {
            if (acts.isEmpty()) { toast("No actions here"); return@runOnUiThread }
            AlertDialog.Builder(this).setTitle("Actions")
                .setItems(acts.take(40).map { it.title }.toTypedArray()) { _, i ->
                    c.resolveEdit(acts[i]) { we -> runOnUiThread {
                        if (we == null) toast("This action has no edit") else applyWorkspaceEdit(we)
                    } }
                }.show()
        } }
    }

    private fun parseEdits(we: JSONObject): Map<String, List<TextEdit>> {
        val res = LinkedHashMap<String, MutableList<TextEdit>>()
        fun add(uri: String, arr: org.json.JSONArray?) {
            if (arr == null || !uri.startsWith("file://")) return
            val l = res.getOrPut(uri.removePrefix("file://")) { ArrayList() }
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val r = o.getJSONObject("range"); val a = r.getJSONObject("start"); val b = r.getJSONObject("end")
                l.add(TextEdit(a.getInt("line"), a.getInt("character"), b.getInt("line"), b.getInt("character"), o.optString("newText")))
            }
        }
        we.optJSONObject("changes")?.let { ch -> ch.keys().forEach { add(it, ch.optJSONArray(it)) } }
        we.optJSONArray("documentChanges")?.let { dc ->
            for (i in 0 until dc.length()) {
                val o = dc.optJSONObject(i) ?: continue
                add(o.optJSONObject("textDocument")?.optString("uri") ?: continue, o.optJSONArray("edits"))
            }
        }
        return res
    }

    /** يطبّق WorkspaceEdit على ملفات المشروع (على القرص) ثم يعيد تحميل الملف الحالي. */
    private fun applyWorkspaceEdit(we: JSONObject) {
        val edits = parseEdits(we)
        if (edits.isEmpty()) { toast("Nothing to change"); return }
        unfoldAll(); save()
        val root = project.canonicalPath
        var n = 0
        for ((path, list) in edits) {
            val f = File(path)
            if (!f.isFile || !f.canonicalPath.startsWith(root)) continue
            var text = f.readText()
            val starts = ArrayList<Int>().apply { add(0); text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
            fun off(l: Int, c: Int) = (starts.getOrElse(l) { text.length } + c).coerceAtMost(text.length)
            for (e in list.sortedWith(compareByDescending<TextEdit> { it.l1 }.thenByDescending { it.c1 })) {
                text = text.replaceRange(off(e.l1, e.c1), off(e.l2, e.c2), e.text)
            }
            f.writeText(text); n++
        }
        dirty = false
        current?.let { openFile(it, reload = true) }
        raSync()
        toast("Changed $n file(s)")
    }

    private fun gotoLoc(l: Loc) {
        val f = File(l.file)
        if (f.canonicalPath.startsWith(project.canonicalPath)) {
            goTo(Diag("note", "", f.relativeTo(project).path, l.line + 1, l.col + 1))
        } else viewExternal(f, l.line)
    }

    /** ملف خارج المشروع (المكتبة القياسية أو حزمة): نعرض مقطعاً للقراءة فقط. */
    private fun viewExternal(f: File, line: Int) {
        if (!f.isFile) { toast("File not available: ${f.name}"); return }
        val all = try { f.readLines() } catch (_: Throwable) { emptyList() }
        val from = maxOf(line - 5, 0)
        val body = all.drop(from).take(160).mapIndexed { i, s -> "%5d  %s".format(from + i + 1, s) }.joinToString("\n")
        showText("${f.name}:${line + 1}", f.path + "\n\n" + body)
    }

    private fun pickLoc(title: String, locs: List<Loc>) {
        val cache = HashMap<String, List<String>>()
        val labels = locs.take(80).map { l ->
            val lines = cache.getOrPut(l.file) { try { File(l.file).readLines() } catch (_: Throwable) { emptyList() } }
            val name = File(l.file).let { if (it.canonicalPath.startsWith(project.canonicalPath)) it.relativeTo(project).path else it.name }
            "$name:${l.line + 1}   ${lines.getOrNull(l.line)?.trim()?.take(60) ?: ""}"
        }
        AlertDialog.Builder(this).setTitle(title).setItems(labels.toTypedArray()) { _, i -> gotoLoc(locs[i]) }.show()
    }

    // ---------- rustfmt ----------
    private fun formatFile() {
        val f = current ?: return
        if (f.extension != "rs") { toast("Rust files only"); return }
        val bin = File(env.prefix, "bin/rustfmt")
        if (!bin.exists()) { toast("rustfmt is not installed"); return }
        unfoldAll(); save()
        val cargo = File(project, "Cargo.toml")
        val ed = Regex("edition\\s*=\\s*\"(\\d+)\"").find(if (cargo.exists()) cargo.readText() else "")?.groupValues?.get(1)
            ?: if (exCourse == "rustlings") "2024" else "2021"
        thread(name = "rustfmt") {
            val pb = ProcessBuilder(bin.path, "--edition", ed, f.path).directory(project).redirectErrorStream(true)
            pb.environment().apply { clear(); putAll(env.environment()) }
            var msg = ""
            val code = try { val p = pb.start(); msg = p.inputStream.bufferedReader().readText(); p.waitFor() } catch (t: Throwable) { msg = t.toString(); -1 }
            runOnUiThread {
                if (code != 0) {
                    toast("rustfmt failed (see Output)"); appendOut("rustfmt:\n$msg\n"); panel.visibility = View.VISIBLE
                    return@runOnUiThread
                }
                val newText = f.readText()
                val e = editor.editableText
                if (newText != e.toString()) {
                    val sel = minOf(editor.selectionStart.coerceAtLeast(0), newText.length)
                    e.replace(0, e.length, newText)
                    editor.setSelection(sel.coerceAtMost(e.length))
                    dirty = false
                    toast("Formatted")
                } else toast("Already formatted")
            }
        }
    }

    // ---------- شجرة الملفات (التابلت) ----------
    private fun refreshTree() {
        val box = treeBox ?: return
        box.removeAllViews()
        box.addView(TextView(this).apply {
            text = project.name; textSize = 15f; setTextColor(Colors.TEXT); setTypeface(null, Typeface.BOLD)
            setPadding(dp(14), dp(10), dp(14), dp(6))
        })
        box.addView(TextView(this).apply {
            text = "+  New file"; textSize = 14f; setTextColor(Colors.ACCENT); setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { newFile() }
        })
        var count = 0
        fun walk(dir: File, depth: Int) {
            val kids = dir.listFiles()?.filter { it.name != "target" && it.name != ".git" && it.name != "dist" }
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: return
            for (f in kids) {
                if (count++ > 600) return
                val rel = f.relativeTo(project).path
                val isCur = f == current
                box.addView(TextView(this).apply {
                    val open = f.isDirectory && rel !in collapsed
                    text = (if (f.isDirectory) (if (open) "\u25BE " else "\u25B8 ") else "   ") + f.name
                    textSize = 14f
                    setTextColor(if (isCur) Colors.ACCENT else if (f.isDirectory) Colors.DIM else Colors.TEXT)
                    setPadding(dp(14 + depth * 14), dp(8), dp(8), dp(8))
                    setOnClickListener {
                        if (f.isDirectory) { if (rel in collapsed) collapsed.remove(rel) else collapsed.add(rel); refreshTree() }
                        else openFile(f)
                    }
                })
                if (f.isDirectory && rel !in collapsed) walk(f, depth + 1)
            }
        }
        walk(project, 0)
    }

    // ---------- شريط الأدوات: قائمة المزيد وزر التشغيل ----------
    private fun fabAction() {
        if (proc != null) { proc?.destroy(); return }
        when {
            exCourse == "exercism" -> runCargo("test")
            exCourse == "rustlings" -> runCargo("run")
            File(project, "app.properties").exists() -> exportApk()
            else -> runCargo("run")
        }
    }

    private fun showMore(anchor: View) {
        val m = android.widget.PopupMenu(this, anchor)
        val acts = ArrayList<() -> Unit>()
        fun item(label: String, f: () -> Unit) { m.menu.add(0, acts.size, acts.size, label); acts.add(f) }
        item("Save") { save(); toast("Saved") }
        item("Format file (rustfmt)") { formatFile() }
        item("Find references") { findReferences() }
        item("Rename symbol...") { renameSymbol() }
        item("Code actions / quick fix") { codeActions() }
        if (exCourse != "rustlings") item("Clippy lints") { runCargo("clippy") }
        item("Build") { runCargo("build") }
        if (exCourse != "rustlings") item("Test") { runCargo("test") }
        if (exCourse == null && File(project, "app.properties").exists()) item("Export APK") { exportApk() }
        item("Output panel") { panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        item("Terminal here") {
            save()
            startActivity(Intent(this, TerminalActivity::class.java).putExtra("cwd", project.path))
        }
        if (exCourse == null) item("Git...") { showGit() }
        item("Settings") { SettingsDialog.show(this) { onSettingsChanged() } }
        m.setOnMenuItemClickListener { acts[it.itemId](); true }
        m.show()
    }

    // ---------- التمارين (Learn) ----------
    private fun exercise(): RlExercise? = LearnContent(env).rustlings().firstOrNull { it.name == exId }

    private fun showText(title: String, text: String) {
        val tv = TextView(this).apply {
            this.text = text; typeface = Typeface.MONOSPACE; textSize = 12f
            setTextIsSelectable(true); setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Close", null).show()
    }

    private fun showHint() {
        val e = exercise() ?: return
        showText("Hint - ${e.name}", e.hint.ifBlank { "No hint for this exercise." })
    }

    private fun showSolution() {
        val e = exercise() ?: return
        AlertDialog.Builder(this).setTitle("Show the solution?")
            .setMessage("Try the hint first. Seeing the solution spoils the exercise.")
            .setPositiveButton("Show") { _, _ ->
                val f = LearnContent(env).rustlingsSolution(e)
                showText("Solution - ${e.name}", if (f.exists()) f.readText() else "Solution not found.")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun resetExercise() {
        val e = exercise() ?: return
        AlertDialog.Builder(this).setTitle("Reset ${e.name}?")
            .setMessage("Your changes to this exercise will be replaced by the original.")
            .setPositiveButton("Reset") { _, _ ->
                val f = LearnContent(env).ensureRustlings(e, reset = true)
                dirty = false; openFile(f, reload = true)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun nextExercise() {
        val lc = LearnContent(env)
        val all = lc.rustlings()
        val i = all.indexOfFirst { it.name == exId }
        if (i < 0 || i + 1 >= all.size) { toast("No more exercises - well done!"); return }
        val n = all[i + 1]
        save()
        exId = n.name
        openFile(lc.ensureRustlings(n))
        toast(n.name)
    }

    private fun runRustlings(sub: String) {
        if (proc != null) { toast("Already running - tap Stop"); return }
        if (!env.isInstalled("base")) { toast("Toolchain not installed (Terminal → Bootstrap)"); return }
        val f = current ?: return
        save()
        val ex = exercise()
        val mode = when { sub == "check" -> "check"; ex?.test == true -> "test"; else -> "run" }
        output.text = ""; diagList.removeAllViews(); panel.visibility = View.VISIBLE
        KeepAliveService.start(this, "exercise")
        thread(name = "exercise") {
            val code = try {
                val pb = ProcessBuilder(File(env.prefix, "bin/bash").path, File(env.prefix, "bin/rustpad-ex").path, f.path, mode)
                    .directory(project).redirectErrorStream(true)
                pb.environment().apply { clear(); putAll(env.environment()) }
                val p = pb.start()
                proc = p
                p.inputStream.bufferedReader().forEachLine { appendOut(it + "\n") }
                p.waitFor()
            } catch (t: Throwable) { appendOut("error: $t\n"); -1 }
            proc = null
            KeepAliveService.stop(this)
            appendOut("[exit $code]\n")
            runOnUiThread {
                if (code == 0 && mode != "check") onSolved()
                else if (code == 0) toast("Compiles - tap Run to check it")
            }
        }
    }

    private fun onSolved() {
        LearnProgress(this).mark("rustlings", exId)
        AlertDialog.Builder(this).setTitle("Solved: $exId")
            .setMessage("Nice work. Continue to the next exercise?")
            .setPositiveButton("Next") { _, _ -> nextExercise() }
            .setNegativeButton("Stay", null).show()
    }

    private fun showInstructions() {
        val f = File(project, ".docs/instructions.md")
        if (!f.exists()) { toast("No instructions"); return }
        save()
        startActivity(Intent(this, MarkdownActivity::class.java)
            .putExtra("path", f.path).putExtra("base", project.path).putExtra("title", exId))
    }

    private fun showExample() {
        AlertDialog.Builder(this).setTitle("Show the example solution?")
            .setMessage("Seeing it spoils the exercise.")
            .setPositiveButton("Show") { _, _ ->
                val f = LearnContent(env).exercismExample(exId)
                showText("Example - $exId", if (f.exists()) f.readText() else "Not found.")
            }.setNegativeButton("Cancel", null).show()
    }

    // ---------- cargo ----------
    // ---------- rust-analyzer (كسول) ----------
    private fun raWanted() = exCourse != "rustlings" && settings.raEnabled && current?.extension == "rs" &&
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
        c.sync(f, editor.fullTextOf(0, editor.text.length))
    }

    private fun onSettingsChanged() {
        editor.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, settings.fontSp)
        if (Colors.needsReload(this)) {
            save()
            current?.let { intent.putExtra("file", it.path) }
            recreate(); return
        }
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
        if (!settings.autoCheck || current?.extension != "rs" || exCourse == "rustlings") return
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
        unfoldAll()
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
        if (exCourse == "rustlings") { if (!silent) runRustlings(sub); return }
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
                    onProcess = { proc = it },
                    trailing = if (sub == "test" && exCourse == "exercism") listOf("--", "--include-ignored") else emptyList())
            } catch (t: Throwable) { appendOut("error: $t\n"); -1 }
            proc = null
            autoRunning = false
            if (!silent) KeepAliveService.stop(this)
            appendOut("[cargo $sub exited with $code — $errors errors, $warnings warnings, -j$jobs]\n")
            if (code == 0 && sub == "test" && exCourse == "exercism") {
                LearnProgress(this).mark("exercism", exId)
                runOnUiThread { toast("All tests pass - solved!") }
            }
            if (code == 137 || code == -9 || code == 143) {
                appendOut("!! The build was killed (probably out of memory). Try: Settings -> turn off rust-analyzer and auto check, close other apps, and rebuild.\n")
            }
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
        if (Regex("^:(\\d+)$").matches(q)) unfoldAll()
        Regex("^:(\\d+)$").find(q)?.let {
            val pos = offsetOf(maxOf(it.groupValues[1].toInt() - 1, 0), 0)
            editor.requestFocus(); editor.setSelection(pos); scrollToOffset(pos); return
        }
        val t = editor.text.toString()
        var idx = if (forward) t.indexOf(q, editor.selectionEnd, true) else t.lastIndexOf(q, editor.selectionStart - 1, true)
        if (idx < 0) idx = if (forward) t.indexOf(q, 0, true) else t.lastIndexOf(q, t.length, true)
        if (idx < 0 && hasFolds()) {
            // قد يكون في كتلة مطوية: افتح الكل وأعد البحث
            val full = editor.fullTextOf(0, editor.text.length)
            if (full.contains(q, true)) { unfoldAll(); findNext(forward); return }
        }
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
        unfoldAll()
        val total = editor.text.count { it == '\n' } + 1
        val line = minOf(n, total)
        val pos = offsetOf(line - 1, 0)
        editor.requestFocus(); editor.setSelection(pos); scrollToOffset(pos)
        if (n > total) toast("File has only $total lines")
    }

    private fun replaceOne() {
        val q = findInput.text.toString()
        if (q.isEmpty()) return
        unfoldAll()
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
        unfoldAll()
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
        unfoldAll()
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
