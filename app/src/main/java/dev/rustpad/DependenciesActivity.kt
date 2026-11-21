package dev.rustpad

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/** مدير التبعيات: عرض/حذف/إضافة مكتبات من crates.io (أو من المكتبات المحمّلة في وضع offline). */
class DependenciesActivity : AppCompatActivity() {
    private class Row(val title: String, val sub: String = "", val header: Boolean = false, val action: (() -> Unit)? = null)

    private lateinit var env: Env
    private lateinit var settings: Settings
    private lateinit var toml: File
    private lateinit var list: ListView
    private lateinit var banner: TextView
    private lateinit var query: EditText
    private var rows: List<Row> = emptyList()
    private var results: List<CrateInfo> = emptyList()
    private var status = ""
    private var vendored: Map<String, List<String>> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Colors.load(this)
        env = Env(this); settings = Settings(this)
        CrashLogger.install(env)
        title = "Dependencies"
        toml = File(intent.getStringExtra("project") ?: run { finish(); return }, "Cargo.toml")
        vendored = CratesApi.vendored(env)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Colors.BG); setPadding(dp(12), dp(8), dp(12), dp(8)) }
        banner = TextView(this).apply { setTextColor(Colors.WARN); textSize = 13f; setPadding(0, dp(4), 0, dp(6)) }
        root.addView(banner)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        query = EditText(this).apply {
            hint = "Search crates (e.g. serde)"; setTextColor(Colors.TEXT); setHintTextColor(Colors.DIM)
            isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, _, _ -> search(); true }
        }
        bar.addView(query, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(Button(this).apply { text = "Search"; setOnClickListener { search() } })
        root.addView(bar)
        list = ListView(this).apply { setBackgroundColor(Colors.BG); divider = null }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refresh()
    }

    private fun offline() = settings.offlineCrates && File(env.prefix, "share/rustpad/vendor").isDirectory

    private fun refresh() {
        banner.text = if (offline())
            "Offline mode is ON: only ${vendored.size} bundled crates can be built. Turn it off in Settings to use any crate from crates.io (needs internet)."
        else "Online mode: crates are downloaded by cargo from crates.io."
        val rs = ArrayList<Row>()
        val deps = if (toml.exists()) CargoToml.list(toml.readText()) else emptyList()
        rs.add(Row("Project dependencies (${deps.size})", header = true))
        for (d in deps) {
            val warn = if (offline() && d.section != "dependencies" || offline() && d.name !in vendored) "  ⚠ not bundled" else ""
            rs.add(Row(d.name, "[${d.section}] ${d.spec}$warn") { confirmRemove(d) })
        }
        if (deps.isEmpty()) rs.add(Row("(none)", "Search above to add one"))
        if (status.isNotEmpty()) rs.add(Row(status, header = true))
        for (c in results) rs.add(Row("${c.name}  ${c.version}", c.desc.take(120) + (if (c.downloads > 0) "  ↓${c.downloads}" else "")) { addDialog(c.name) })
        rows = rs
        list.adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(i: Int) = rows[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, v: View?, p: ViewGroup?): View {
                val r = rows[i]
                val l = LinearLayout(this@DependenciesActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(if (r.header) 14 else 8), dp(8), dp(8))
                }
                l.addView(TextView(this@DependenciesActivity).apply {
                    text = r.title; textSize = if (r.header) 13f else 16f
                    setTextColor(if (r.header) Colors.ACCENT else Colors.TEXT)
                    if (r.header) typeface = Typeface.DEFAULT_BOLD
                })
                if (r.sub.isNotEmpty()) l.addView(TextView(this@DependenciesActivity).apply {
                    text = r.sub; textSize = 12f; setTextColor(if (r.sub.contains("⚠")) Colors.WARN else Colors.DIM)
                })
                return l
            }
        }
        list.setOnItemClickListener { _, _, i, _ -> rows[i].action?.invoke() }
    }

    private fun search() {
        val q = query.text.toString().trim()
        if (q.isEmpty()) return
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(query.windowToken, 0)
        if (offline()) {
            results = vendored.filterKeys { it.contains(q, true) }.map { CrateInfo(it.key, it.value.last(), "bundled (offline)", 0) }.take(50)
            status = "Bundled crates matching \"$q\" (${results.size})"; refresh(); return
        }
        status = "Searching..."; results = emptyList(); refresh()
        Thread {
            try {
                val r = CratesApi.search(q)
                runOnUiThread { results = r; status = "crates.io results for \"$q\" (${r.size})"; refresh() }
            } catch (e: Throwable) {
                runOnUiThread {
                    results = vendored.filterKeys { it.contains(q, true) }.map { CrateInfo(it.key, it.value.last(), "bundled (offline)", 0) }.take(50)
                    status = "No internet (${e.message}). Showing bundled crates (${results.size})"; refresh()
                }
            }
        }.start()
    }

    private fun confirmRemove(d: Dep) {
        AlertDialog.Builder(this).setTitle("Remove ${d.name}?").setMessage("[${d.section}] ${d.spec}")
            .setPositiveButton("Remove") { _, _ ->
                toml.writeText(CargoToml.remove(toml.readText(), d.section, d.name)); refresh()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun addDialog(name: String) {
        val pd = ProgressDialog(this, "Loading $name...")
        Thread {
            var vers: List<String> = emptyList(); var feats: Map<String, Boolean> = emptyMap()
            if (!offline()) {
                try { vers = CratesApi.versions(name); if (vers.isNotEmpty()) feats = CratesApi.features(name, vers[0]) } catch (_: Throwable) {}
            }
            if (vers.isEmpty()) vers = vendored[name]?.reversed() ?: emptyList()
            runOnUiThread { pd.dismiss(); if (vers.isEmpty()) toast("Could not load versions (no internet?)") else showAdd(name, vers, feats) }
        }.start()
    }

    private fun showAdd(name: String, vers: List<String>, feats: Map<String, Boolean>) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        val verSp = Spinner(this).apply { adapter = ArrayAdapter(this@DependenciesActivity, android.R.layout.simple_spinner_dropdown_item, vers) }
        val kindSp = Spinner(this).apply { adapter = ArrayAdapter(this@DependenciesActivity, android.R.layout.simple_spinner_dropdown_item, CargoToml.SECTIONS) }
        val defCb = CheckBox(this).apply { text = "default features"; isChecked = true }
        box.addView(TextView(this).apply { text = "Version" }); box.addView(verSp)
        box.addView(TextView(this).apply { text = "Section" }); box.addView(kindSp)
        box.addView(defCb)
        val cbs = LinkedHashMap<String, CheckBox>()
        if (feats.isNotEmpty()) {
            box.addView(TextView(this).apply { text = "Features"; setPadding(0, dp(8), 0, 0) })
            for ((f, isDef) in feats) cbs[f] = CheckBox(this).apply { text = if (isDef) "$f (default)" else f }.also { box.addView(it) }
        }
        AlertDialog.Builder(this).setTitle("Add $name").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Add") { _, _ ->
                val ver = verSp.selectedItem as String
                val fs = cbs.filter { it.value.isChecked }.keys.toList()
                val spec = CargoToml.spec(ver, fs, defCb.isChecked)
                val sec = kindSp.selectedItem as String
                toml.writeText(CargoToml.add(toml.readText(), sec, name, spec))
                setResult(RESULT_OK)
                if (offline() && name !in vendored) toast("Added, but $name is not bundled: it won't build in offline mode")
                else toast("Added $name")
                refresh()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun ProgressDialog(ctx: android.content.Context, msg: String): AlertDialog =
        AlertDialog.Builder(ctx).setMessage(msg).setCancelable(false).show()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
