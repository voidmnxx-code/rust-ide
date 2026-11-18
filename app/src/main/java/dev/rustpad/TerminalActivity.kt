package dev.rustpad

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.concurrent.Executors

class TerminalActivity : AppCompatActivity() {

    private lateinit var env: Env
    private lateinit var perms: PermissionManager
    private lateinit var out: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private val io = Executors.newSingleThreadExecutor()
    private var session: ShellSession? = null
    private val ansi = Regex("\u001B\\[[0-9;?]*[A-Za-z]")

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) importBootstrap(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        env = Env(this)
        perms = PermissionManager(this)
        env.ensureDirs()
        CrashLogger.install(env)
        buildUi()
        CrashLogger.consume(env)?.let { append("!!! previous crash:\n$it\n") }
        if (env.logFile.exists()) {
            val tail = env.logFile.readLines().takeLast(15).joinToString("\n")
            append("--- last session log (tail) ---\n$tail\n--------------------------------\n")
            env.logFile.delete()
        }

        append("RustPad 0.1 (phase 1)\nHOME=${env.home}\nPREFIX=${env.prefix}\n")
        if (!env.isInstalled("base")) {
            append("Bootstrap not installed. Tap [Bootstrap] to download or [Import] to use a local file.\n")
        }
        if (!perms.hasStorageAccess()) perms.requestStorageAccess() else env.linkSharedStorage()
        startShell()
    }

    override fun onResume() {
        super.onResume()
        if (perms.hasStorageAccess()) env.linkSharedStorage()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (perms.hasStorageAccess()) env.linkSharedStorage()
    }

    override fun onDestroy() {
        session?.stop()
        io.shutdown()
        super.onDestroy()
    }

    // ---------- UI (برمجياً لتقليل عدد الملفات) ----------
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(16, 16, 16, 16)
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(label: String, action: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; textSize = 12f
            setOnClickListener { action() }
            bar.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        btn("Bootstrap") { runBootstrap() }
        btn("Import") { importLauncher.launch(arrayOf("*/*")) }
        btn("Permissions") { requestPermissions() }
        btn("Clear") { out.text = "" }
        root.addView(bar)

        out = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 12f
            setTextColor(Color.parseColor("#E0E0E0"))
            setTextIsSelectable(true)
            gravity = Gravity.START
        }
        scroll = ScrollView(this).apply { addView(out) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        input = EditText(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 14f
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            hint = "\$ command"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEND ||
                    event?.keyCode == KeyEvent.KEYCODE_ENTER
                ) { submit(); true } else false
            }
        }
        root.addView(input, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun submit() {
        val line = input.text.toString()
        input.setText("")
        append("\$ $line\n")
        session?.send(line)
    }

    private fun append(text: String) {
        try { synchronized(env) { env.logFile.appendText(text) } } catch (_: Throwable) {}
        runOnUiThread {
            out.append(ansi.replace(text, ""))
            if (out.length() > 200_000) out.text = out.text.takeLast(100_000)
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    // ---------- الشل ----------
    private fun startShell() {
        session?.stop()
        val dir = intent.getStringExtra("cwd")?.let { File(it) }?.takeIf { it.isDirectory } ?: env.home
        session = ShellSession(env, dir, ::append) { code -> append("\n[shell exited: $code]\n") }
        try { session?.start() } catch (e: Exception) { append("shell error: ${e.message}\n") }
    }

    // ---------- Bootstrap ----------
    private fun runBootstrap() {
        runOnUiThread { window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        KeepAliveService.start(this, "Installing bootstrap...")
        io.execute {
          try {
            val installer = BootstrapInstaller(env)
            for (stage in BootstrapConfig.STAGES) {
                if (env.isInstalled(stage.id)) { append("[${stage.id}] already installed\n"); continue }
                try {
                    installer.installStage(stage) { append(it + "\n") }
                } catch (e: Exception) {
                    append("[${stage.id}] failed: ${e.message}\n")
                    if (!stage.optional) break
                }
            }
          } catch (t: Throwable) {
            append("FATAL: $t\n${t.stackTrace.take(8).joinToString("\n")}\n")
          } finally {
            KeepAliveService.stop(this)
            runOnUiThread {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                startShell()
            }
          }
        }
    }

    /** اسم المرحلة من اسم الملف: bootstrap-<stage>-aarch64.tar.zst (مع تحمّل (1) وما شابه). */
    private fun stageIdFor(uri: Uri): String? {
        val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: uri.lastPathSegment?.substringAfterLast('/') ?: return null
        val known = BootstrapConfig.STAGES.map { it.id }
        // الأطول أولاً حتى لا يُلتقط "base" من اسم آخر
        return known.sortedByDescending { it.length }.firstOrNull { name.contains("-$it-") || name.contains("bootstrap-$it") }
    }

    private fun importBootstrap(uris: List<Uri>) {
        io.execute {
            try {
                // الترتيب المنطقي: base ثم apk-tools ثم rust-analyzer
                val order = BootstrapConfig.STAGES.map { it.id }
                val items = uris.map { it to stageIdFor(it) }
                for ((uri, id) in items.sortedBy { order.indexOf(it.second ?: "") }) {
                    if (id == null) {
                        append("skipped: could not tell the stage from the file name (expected bootstrap-<stage>-aarch64.tar.zst)\n")
                        continue
                    }
                    contentResolver.openInputStream(uri)?.use {
                        BootstrapInstaller(env).installFromStream(id, it) { m -> append(m + "\n") }
                    }
                }
                runOnUiThread { startShell() }
            } catch (e: Throwable) {
                append("import failed: $e\n")
            }
        }
    }

    private fun requestPermissions() {
        if (!perms.hasStorageAccess()) perms.requestStorageAccess()
        else append("storage: granted\n")
        if (!perms.isIgnoringBatteryOptimizations()) perms.requestBatteryExemption()
        else append("battery exemption: granted\n")
    }
}
