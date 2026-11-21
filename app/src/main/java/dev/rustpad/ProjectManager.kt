package dev.rustpad

import java.io.File

/** مشاريع Cargo داخل ~/projects. */
class ProjectManager(private val env: Env, private val assets: android.content.res.AssetManager? = null) {
    private val root get() = File(env.home, "projects").apply { mkdirs() }

    fun list(): List<File> =
        root.listFiles { f -> f.isDirectory && File(f, "Cargo.toml").exists() }
            ?.sortedBy { it.name } ?: emptyList()

    fun isValidName(name: String) = Regex("[a-z][a-z0-9_-]{0,40}").matches(name)

    /** ينشئ مشروعاً ثنائياً بإعدادات خفيفة للبناء على الهاتف. يعيد null إن كان موجوداً. */
    fun create(name: String): File? {
        val dir = File(root, name)
        if (dir.exists()) return null
        File(dir, "src").mkdirs()
        File(dir, "Cargo.toml").writeText(
            """
            [package]
            name = "$name"
            version = "0.1.0"
            edition = "2021"

            [dependencies]

            [profile.dev]
            opt-level = 1
            debug = 0
            incremental = true
            """.trimIndent() + "\n"
        )
        File(dir, "src/main.rs").writeText(
            "fn main() {\n    println!(\"Hello from RustPad!\");\n}\n"
        )
        File(dir, ".gitignore").writeText("target/\n")
        return dir
    }

    /** مشروع تطبيق Android: مكتبة Rust (cdylib) داخل قشرة جاهزة. */
    fun createAndroid(name: String): File? {
        val dir = File(root, name)
        if (dir.exists()) return null
        File(dir, "src").mkdirs()
        val pkgSuffix = name.replace('-', '_')
        File(dir, "Cargo.toml").writeText(
            """
            [package]
            name = "$pkgSuffix"
            version = "0.1.0"
            edition = "2021"

            [lib]
            crate-type = ["cdylib"]

            [dependencies]

            [profile.dev]
            opt-level = 1
            debug = 0
            incremental = true

            [profile.release]
            opt-level = 2
            strip = true
            """.trimIndent() + "\n"
        )
        File(dir, "app.properties").writeText(
            "package=dev.rustpad.apps.$pkgSuffix\nlabel=${name}\nversion_code=1\nversion_name=1.0\npermissions=INTERNET,ACCESS_NETWORK_STATE\nauto_increment=true\nsigning=local\n"
        )
        for (f in listOf("lib.rs", "ui.rs")) {
            val text = assets?.open("templates/android/$f")?.bufferedReader()?.readText()
                ?: error("template asset missing: $f")
            File(dir, "src/$f").writeText(text)
        }
        File(dir, "res").mkdirs()
        assets?.open("templates/android/logo.png")?.use { ins ->
            File(dir, "res/logo.png").outputStream().use { ins.copyTo(it) }
        }
        File(dir, ".gitignore").writeText("target/\ndist/\n")
        return dir
    }

    fun delete(dir: File) = dir.deleteRecursively()
}
