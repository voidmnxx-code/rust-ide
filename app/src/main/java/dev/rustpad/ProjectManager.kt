package dev.rustpad

import java.io.File

/** مشاريع Cargo داخل ~/projects. */
class ProjectManager(private val env: Env) {
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

    fun delete(dir: File) = dir.deleteRecursively()
}
