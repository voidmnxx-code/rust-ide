package dev.rustpad

import android.content.Context
import android.system.Os
import java.io.File

/** ينسخ سكربت التصدير وقشرة التطبيق (classes.dex) من assets إلى $PREFIX عند كل تشغيل. */
object AssetInstaller {
    fun install(ctx: Context, env: Env) {
        try {
            env.ensureDirs()
            val bin = File(env.prefix, "bin").apply { mkdirs() }
            for (name in listOf("rustpad-export", "rustpad-warm", "rustpad-crates", "rustpad-selftest")) {
                val script = ctx.assets.open("$name.sh").bufferedReader().readText()
                    .replace("@PREFIX@", env.prefix.path)
                val target = File(bin, name)
                target.writeText(script)
                Os.chmod(target.path, 0x1ED) // 0755
            }

            val shellDir = File(env.prefix, "share/rustpad/shell").apply { mkdirs() }
            ctx.assets.list("shell")?.forEach { name ->
                ctx.assets.open("shell/$name").use { ins ->
                    File(shellDir, name).outputStream().use { ins.copyTo(it) }
                }
            }
            linkJvmTools(env)
        } catch (_: Throwable) {
            // أصول مفقودة (بناء محلي بلا القشرة): يظهر الخطأ عند التصدير بدل الانهيار.
        }
    }

    /** حزمة Termux تنشئ bin/java وغيرها في postinst؛ ننشئها هنا إن كانت مفقودة (java, keytool, ...). */
    private fun linkJvmTools(env: Env) {
        val bin = File(env.prefix, "bin")
        File(env.prefix, "lib/jvm").listFiles()?.forEach { jdk ->
            File(jdk, "bin").listFiles()?.forEach { tool ->
                val link = File(bin, tool.name)
                if (!link.exists() && !java.nio.file.Files.isSymbolicLink(link.toPath())) {
                    try { Os.symlink("../lib/jvm/${jdk.name}/bin/${tool.name}", link.path) } catch (_: Throwable) {}
                }
            }
        }
    }
}
