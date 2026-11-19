package dev.rustpad

import java.io.File

/** يدير ~/.cargo/config.toml (استبدال crates.io بالمجلد المحمّل مسبقاً). لا يلمس ملفاً كتبه المستخدم. */
object CargoConfig {
    private const val HEADER = "# managed by RustPad (Settings -> Offline crates). Remove this line to take over the file.\n"

    fun sync(env: Env, s: Settings) {
        try {
            val f = File(env.home, ".cargo/config.toml")
            if (f.exists() && !f.readText().startsWith(HEADER.trim())) return
            val vendor = File(env.prefix, "share/rustpad/vendor")
            val sb = StringBuilder(HEADER)
            if (s.offlineCrates && vendor.isDirectory) {
                sb.append("[source.crates-io]\nreplace-with = \"rustpad-vendor\"\n\n")
                sb.append("[source.rustpad-vendor]\ndirectory = \"${vendor.path}\"\n")
            }
            val text = sb.toString()
            f.parentFile?.mkdirs()
            if (!f.exists() || f.readText() != text) f.writeText(text)
        } catch (_: Throwable) {
        }
    }

    /** متغيرات البيئة الإضافية للبناء (sccache، lld). */
    fun buildEnv(env: Env, s: Settings): Map<String, String> {
        val m = HashMap<String, String>()
        if (s.useLld) m["RUSTFLAGS"] = "-C link-arg=-fuse-ld=lld"
        val sccache = File(env.prefix, "bin/sccache")
        if (s.sccache && sccache.exists()) {
            m["RUSTC_WRAPPER"] = sccache.path
            m["SCCACHE_DIR"] = File(env.home, ".cache/sccache").path
            m["SCCACHE_CACHE_SIZE"] = "4G"
        }
        return m
    }
}
