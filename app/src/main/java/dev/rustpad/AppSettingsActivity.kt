package dev.rustpad

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/** إعدادات التطبيق المُصدَّر: الاسم، الحزمة، الإصدار، الأذونات، الأيقونة، التوقيع. */
class AppSettingsActivity : AppCompatActivity() {
    private lateinit var project: File
    private lateinit var props: AppProps
    private lateinit var label: EditText
    private lateinit var pkg: EditText
    private lateinit var vname: EditText
    private lateinit var vcode: EditText
    private lateinit var auto: CheckBox
    private lateinit var release: CheckBox
    private lateinit var iconView: ImageView
    private val perms = LinkedHashMap<String, CheckBox>()

    companion object {
        const val PICK = 77
        val PERMS = listOf(
            "INTERNET" to "Internet",
            "ACCESS_NETWORK_STATE" to "Network state",
            "CAMERA" to "Camera",
            "ACCESS_FINE_LOCATION" to "Precise location (GPS)",
            "ACCESS_COARSE_LOCATION" to "Approximate location",
            "RECORD_AUDIO" to "Microphone",
            "VIBRATE" to "Vibrate",
            "POST_NOTIFICATIONS" to "Notifications (Android 13+)",
            "READ_MEDIA_IMAGES" to "Read images (Android 13+)",
            "WAKE_LOCK" to "Keep awake",
            "BLUETOOTH_CONNECT" to "Bluetooth",
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Colors.load(this)
        CrashLogger.install(Env(this))
        title = "App settings"
        project = File(intent.getStringExtra("project") ?: run { finish(); return })
        props = AppProps(File(project, "app.properties"))

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(24)) }
        fun lbl(t: String) = box.addView(TextView(this).apply { text = t; setTextColor(Colors.DIM); textSize = 12f; setPadding(0, dp(12), 0, 0) })
        fun field(v: String, num: Boolean = false) = EditText(this).apply {
            setText(v); setTextColor(Colors.TEXT); isSingleLine = true
            if (num) inputType = InputType.TYPE_CLASS_NUMBER
        }.also { box.addView(it) }
        lbl("App name"); label = field(props["label", project.name])
        lbl("Package name (unique id, e.g. com.me.app)"); pkg = field(props["package"])
        lbl("Version name"); vname = field(props["version_name", "1.0"])
        lbl("Version code (integer, must grow with every update)"); vcode = field(props["version_code", "1"], true)
        auto = CheckBox(this).apply { text = "Increase version code after each export"; setTextColor(Colors.TEXT); isChecked = props["auto_increment"] == "true" }
        box.addView(auto)

        lbl("Icon")
        val iconRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        iconView = ImageView(this)
        iconRow.addView(iconView, LinearLayout.LayoutParams(dp(64), dp(64)))
        iconRow.addView(Button(this).apply { text = "Choose image..."; isAllCaps = false
            setOnClickListener { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), PICK) } })
        box.addView(iconRow)
        loadIcon()

        lbl("Permissions (declared in the APK)")
        val have = props["permissions"].split(',').map { it.trim().removePrefix("android.permission.") }.filter { it.isNotEmpty() }.toSet()
        for ((k, d) in PERMS) {
            perms[k] = CheckBox(this).apply { text = d; setTextColor(Colors.TEXT); isChecked = k in have }.also { box.addView(it) }
        }

        lbl("Signing")
        release = CheckBox(this).apply { text = "Sign with a RELEASE key (created on first export)"; setTextColor(Colors.TEXT); isChecked = props["signing"] == "release" }
        box.addView(release)
        box.addView(TextView(this).apply {
            text = "A new key is needed once: an app can only be updated by an APK signed with the same key. Use 'Back up keys' below and keep the copy safe."
            setTextColor(Colors.DIM); textSize = 12f
        })
        box.addView(Button(this).apply { text = "Back up keys to Download"; isAllCaps = false; setOnClickListener { backupKeys() } })

        box.addView(Button(this).apply { text = "Save"; setOnClickListener { save(); finish() } })
        setContentView(ScrollView(this).apply { setBackgroundColor(Colors.BG); addView(box) })
    }

    private fun loadIcon() {
        val f = listOf("res/icon.png", "res/logo.png").map { File(project, it) }.firstOrNull { it.exists() }
        if (f != null) BitmapFactory.decodeFile(f.path)?.let { iconView.setImageBitmap(it) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val bmp = contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) } ?: error("not an image")
            val s = Bitmap.createScaledBitmap(bmp, 192, 192, true)
            File(project, "res").mkdirs()
            File(project, "res/icon.png").outputStream().use { s.compress(Bitmap.CompressFormat.PNG, 100, it) }
            loadIcon()
        } catch (t: Throwable) { Toast.makeText(this, "Could not use this image: ${t.message}", Toast.LENGTH_LONG).show() }
    }

    private fun backupKeys() {
        val env = Env(this)
        val dir = File(env.home, ".rustpad")
        val out = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "rustpad-keys")
        try {
            out.mkdirs()
            var n = 0
            dir.listFiles { f -> f.name.endsWith(".jks") || f.name.endsWith(".pass") }?.forEach { it.copyTo(File(out, it.name), true); n++ }
            Toast.makeText(this, if (n == 0) "No keys yet (export once first)" else "Copied $n file(s) to Download/rustpad-keys", Toast.LENGTH_LONG).show()
        } catch (t: Throwable) { Toast.makeText(this, "Backup failed: ${t.message}", Toast.LENGTH_LONG).show() }
    }

    private fun save() {
        val p = pkg.text.toString().trim()
        if (!Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+").matches(p)) {
            Toast.makeText(this, "Package must look like com.example.app (lowercase)", Toast.LENGTH_LONG).show(); return
        }
        props["label"] = label.text.toString().trim().ifEmpty { project.name }
        props["package"] = p
        props["version_name"] = vname.text.toString().trim().ifEmpty { "1.0" }
        props["version_code"] = (vcode.text.toString().toIntOrNull() ?: 1).coerceAtLeast(1).toString()
        props["auto_increment"] = auto.isChecked.toString()
        props["signing"] = if (release.isChecked) "release" else "local"
        props["permissions"] = perms.filter { it.value.isChecked }.keys.joinToString(",")
        props.save()
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
    }
}
