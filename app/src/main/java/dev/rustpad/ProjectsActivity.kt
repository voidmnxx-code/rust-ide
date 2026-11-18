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
    private lateinit var list: ListView
    private lateinit var banner: TextView
    private var projects: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        env = Env(this)
        env.ensureDirs()
        CrashLogger.install(env)
        pm = ProjectManager(env)
        AssetInstaller.install(this, env)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Colors.BG)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "RustPad"; textSize = 24f; setTextColor(Colors.TEXT)
        })
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(flatButton("+ New project") { newProject() })
        bar.addView(flatButton("Terminal") {
            startActivity(Intent(this, TerminalActivity::class.java))
        })
        root.addView(bar)

        banner = TextView(this).apply {
            setTextColor(Colors.WARN); textSize = 13f
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(banner)

        list = ListView(this).apply { setBackgroundColor(Colors.BG) }
        list.setOnItemClickListener { _, _, i, _ -> open(projects[i]) }
        list.setOnItemLongClickListener { _, _, i, _ -> confirmDelete(projects[i]); true }
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
        list.adapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_1, projects.map { it.name }
        ) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup) =
                (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(Colors.TEXT); textSize = 16f
                }
        }
        if (projects.isEmpty()) banner.append("\nNo projects yet. Tap [+ New project].")
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
