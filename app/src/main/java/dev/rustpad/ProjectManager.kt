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
            "package=dev.rustpad.apps.$pkgSuffix\nlabel=${name}\nversion_code=1\nversion_name=1.0\n"
        )
        File(dir, "src/lib.rs").writeText(ANDROID_LIB_RS)
        File(dir, ".gitignore").writeText("target/\ndist/\n")
        return dir
    }

    fun delete(dir: File) = dir.deleteRecursively()

    companion object {
        // يطابق أسماء الدوال في قشرة التطبيق: dev.rustpad.shell.Native
        private val ANDROID_LIB_RS = """
use std::ffi::{c_char, c_void, CString};
use std::sync::atomic::{AtomicI32, Ordering};

// JNIEnv* هو مؤشر إلى جدول دوال؛ NewStringUTF هي الدالة رقم 167 في الجدول.
type JEnv = *mut *const *const c_void;
type JObject = *mut c_void;

unsafe fn new_string(env: JEnv, s: &str) -> JObject {
    let f: unsafe extern "system" fn(JEnv, *const c_char) -> JObject =
        std::mem::transmute(*(*env).add(167));
    let c = CString::new(s).unwrap();
    f(env, c.as_ptr())
}

static CLICKS: AtomicI32 = AtomicI32::new(0);

#[no_mangle]
pub extern "system" fn Java_dev_rustpad_shell_Native_hello(env: JEnv, _class: JObject) -> JObject {
    unsafe { new_string(env, "Hello from Rust on Android!") }
}

#[no_mangle]
pub extern "system" fn Java_dev_rustpad_shell_Native_click(env: JEnv, _class: JObject) -> JObject {
    let n = CLICKS.fetch_add(1, Ordering::SeqCst) + 1;
    unsafe { new_string(env, &format!("Rust counted {} click(s)", n)) }
}
""".trimStart()
    }
}
