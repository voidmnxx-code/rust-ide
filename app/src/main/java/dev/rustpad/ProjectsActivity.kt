package dev.rustpad

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class ProjectsActivity : AppCompatActivity() {
    private lateinit var env: Env
    private lateinit var pm: ProjectManager
    private lateinit var list: android.widget.GridView
    private lateinit var banner: TextView
    private var projects: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        env = Env(this)
        env.ensureDirs()
        CrashLogger.install(env)
        pm = ProjectManager(env, assets)
        AssetInstaller.install(this, env)
        CargoConfig.sync(env, Settings(this))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Colors.BG)
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        // الرأس: العنوان + قائمة الأدوات
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = "RustPad"; textSize = 26f; setTextColor(Colors.TEXT); setTypeface(null, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        lateinit var menuBtn: android.view.View
        menuBtn = iconButton("\u22EF", "Tools") { toolsMenu(menuBtn) }
        head.addView(menuBtn)
        root.addView(head)

        // الإجراءان الرئيسيان
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(8)) }
        actions.addView(TextView(this).apply {
            text = "+  New project"; textSize = 16f; setTextColor(Color.WHITE); gravity = android.view.Gravity.CENTER
            minHeight = dp(52); background = roundBg(Colors.ACCENT, 14)
            setOnClickListener { newProject() }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        actions.addView(TextView(this).apply {
            text = "Learn Rust"; textSize = 16f; setTextColor(Colors.TEXT); gravity = android.view.Gravity.CENTER
            minHeight = dp(52); background = roundBg(Colors.CARD, 14, Colors.DIM, 1)
            setOnClickListener { startActivity(Intent(this@ProjectsActivity, LearnActivity::class.java)) }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        root.addView(actions)

        banner = TextView(this).apply {
            setTextColor(Colors.WARN); textSize = 13f
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(banner)

        // عدد الأعمدة حسب عرض الشاشة: هاتف 1، تابلت 2-3
        val wDp = resources.configuration.screenWidthDp
        val cols = when { wDp >= 900 -> 3; wDp >= 600 -> 2; else -> 1 }
        list = android.widget.GridView(this).apply {
            setBackgroundColor(Colors.BG); numColumns = cols
            horizontalSpacing = dp(12); verticalSpacing = dp(12); selector = android.graphics.drawable.ColorDrawable(0)
            clipToPadding = false; setPadding(0, 0, 0, dp(24))
        }
        list.setOnItemClickListener { _, _, i, _ -> open(projects[i]) }
        list.setOnItemLongClickListener { _, _, i, _ -> projectMenu(projects[i]); true }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        projects = pm.list()
        banner.text = if (!env.isInstalled("base"))
            "Toolchain not installed. Open Terminal and tap [Bootstrap] first." else ""
        list.adapter = object : ArrayAdapter<File>(this, 0, projects) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val d = projects[position]
                val kind = when {
                    File(d, "app.properties").exists() -> "Android app"
                    File(d, "src/lib.rs").exists() && !File(d, "src/main.rs").exists() -> "Library"
                    else -> "Console app"
                }
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = roundBg(Colors.CARD, 14)
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    minimumHeight = dp(76)
                }
                card.addView(TextView(context).apply {
                    text = d.name; textSize = 18f; setTextColor(Colors.TEXT); setTypeface(null, android.graphics.Typeface.BOLD)
                })
                card.addView(TextView(context).apply {
                    text = "$kind  \u00B7  ${ago(lastEdit(d))}"; textSize = 13f; setTextColor(Colors.DIM)
                })
                return card
            }
        }
        if (projects.isEmpty()) banner.append("\nNo projects yet. Tap [+ New project].")
    }

    private fun backupAll() {
        val perm = PermissionManager(this)
        val ext = perm.hasStorageAccess()
        val bm = BackupManager(env)
        Thread {
            val res = pm.list().joinToString("\n") { "${it.name}: ${bm.backup(it, ext)}" }
            runOnUiThread {
                AlertDialog.Builder(this).setTitle("Backup")
                    .setMessage((if (res.isEmpty()) "No projects." else res) +
                        if (ext) "\n\nShared copy: ${bm.externalRoot.path}" else "\n\nStorage access is off: only the internal copy was made (lost if the app is uninstalled). Grant it from Terminal -> Permissions.")
                    .setPositiveButton("OK", null).show()
            }
        }.start()
    }

    private fun restoreDialog() {
        val bm = BackupManager(env)
        val list = bm.available()
        if (list.isEmpty()) {
            Toast.makeText(this, "No backups found", Toast.LENGTH_LONG).show(); return
        }
        val names = list.map { "${it.project}  (${it.file.name.removeSuffix(".zip")})" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Restore a project").setItems(names) { _, i ->
            Thread {
                val d = try { bm.restore(list[i], File(env.home, "projects").apply { mkdirs() }) } catch (t: Throwable) { null }
                runOnUiThread {
                    Toast.makeText(this, if (d != null) "Restored: ${d.name}" else "Restore failed", Toast.LENGTH_LONG).show()
                    refresh()
                }
            }.start()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun lastEdit(d: File): Long =
        listOf(File(d, "src"), File(d, "Cargo.toml")).maxOf { f ->
            if (f.isDirectory) (f.walkTopDown().filter { it.isFile }.maxOfOrNull { it.lastModified() } ?: f.lastModified())
            else f.lastModified()
        }

    private fun ago(t: Long): String {
        val m = (System.currentTimeMillis() - t) / 60000
        return when {
            m < 1 -> "just now"
            m < 60 -> "$m min ago"
            m < 60 * 24 -> "${m / 60} h ago"
            m < 60 * 24 * 30 -> "${m / (60 * 24)} d ago"
            else -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(t))
        }
    }

    private fun toolsMenu(anchor: android.view.View) {
        val m = android.widget.PopupMenu(this, anchor)
        val acts = ArrayList<() -> Unit>()
        fun item(label: String, f: () -> Unit) { m.menu.add(0, acts.size, acts.size, label); acts.add(f) }
        fun term(cmd: String? = null) = startActivity(Intent(this, TerminalActivity::class.java).apply { if (cmd != null) putExtra("cmd", cmd) })
        item("Terminal") { term() }
        item("Clone repository...") { cloneDialog() }
        item("Backup all projects") { backupAll() }
        item("Restore from backup...") { restoreDialog() }
        item("Settings") { SettingsDialog.show(this) { CargoConfig.sync(env, Settings(this)) } }
        item("Installed crates") { term("rustpad-crates | less") }
        item("Warm build cache") { term("rustpad-warm") }
        item("Self-test") { term("rustpad-selftest") }
        m.setOnMenuItemClickListener { acts[it.itemId](); true }
        m.show()
    }

    private fun projectMenu(dir: File) {
        AlertDialog.Builder(this).setTitle(dir.name)
            .setItems(arrayOf("Open", "Backup now", "Delete...")) { _, i ->
                when (i) {
                    0 -> open(dir)
                    1 -> Thread {
                        val r = BackupManager(env).backup(dir, PermissionManager(this).hasStorageAccess())
                        runOnUiThread { Toast.makeText(this, r, Toast.LENGTH_LONG).show() }
                    }.start()
                    2 -> confirmDelete(dir)
                }
            }.show()
    }

    private fun open(dir: File) {
        startActivity(Intent(this, EditorActivity::class.java).putExtra("project", dir.path))
    }

    private fun newProject() {
        val input = EditText(this).apply {
            hint = "project_name"; inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.BLACK)
        }
        AlertDialog.Builder(this).setTitle("New project").setView(input)
            .setPositiveButton("Console app") { _, _ -> create(input.text.toString().trim(), false) }
            .setNeutralButton("Android app") { _, _ -> create(input.text.toString().trim(), true) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun cloneDialog() {
        val input = EditText(this).apply { hint = "https://github.com/user/repo.git"; setTextColor(Color.BLACK) }
        AlertDialog.Builder(this).setTitle("Clone repository").setView(input)
            .setPositiveButton("Clone") { _, _ ->
                val url = input.text.toString().trim()
                if (!Regex("^https://[A-Za-z0-9._~:/@%+-]+$").matches(url)) {
                    Toast.makeText(this, "Only plain https URLs are supported", Toast.LENGTH_LONG).show()
                } else {
                    startActivity(Intent(this, TerminalActivity::class.java)
                        .putExtra("cmd", "mkdir -p ~/projects && cd ~/projects && git clone '$url' && echo CLONE-DONE"))
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun create(name: String, android: Boolean) {
        if (!pm.isValidName(name)) {
            Toast.makeText(this, "Use a-z, 0-9, _ or - (start with a letter)", Toast.LENGTH_LONG).show()
            return
        }
        val d = if (android) pm.createAndroid(name) else pm.create(name)
        if (d == null) Toast.makeText(this, "Already exists", Toast.LENGTH_SHORT).show()
        else { refresh(); open(d) }
    }

    private fun confirmDelete(dir: File) {
        AlertDialog.Builder(this).setTitle("Delete ${dir.name}?")
            .setMessage("All files in the project will be removed.")
            .setPositiveButton("Delete") { _, _ -> pm.delete(dir); refresh() }
            .setNegativeButton("Cancel", null).show()
    }
}
