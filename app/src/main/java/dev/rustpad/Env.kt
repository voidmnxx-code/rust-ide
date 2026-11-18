package dev.rustpad

import android.content.Context
import android.os.Environment
import android.system.ErrnoException
import android.system.Os
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** مسارات البيئة (أسلوب Termux): home للمشاريع، usr للأدوات. */
class Env(context: Context) {
    val home = File(context.filesDir, "home")
    val prefix = File(context.filesDir, "usr")
    val tmp = File(context.filesDir, "tmp")

    fun ensureDirs() {
        listOf(home, prefix, tmp, File(prefix, "bin")).forEach { it.mkdirs() }
    }

    fun isInstalled(id: String) = File(prefix, ".installed-$id").exists()
    fun markInstalled(id: String) { File(prefix, ".installed-$id").writeText("ok") }

    fun shellPath(): String {
        for (name in listOf("bash", "sh")) {
            val f = File(prefix, "bin/$name")
            if (f.exists() && f.canExecute()) return f.path
        }
        return "/system/bin/sh"
    }

    fun environment(): Map<String, String> = mapOf(
        "HOME" to home.path,
        "PREFIX" to prefix.path,
        "TMPDIR" to tmp.path,
        "PATH" to "${prefix.path}/bin:/system/bin:/system/xbin",
        "LANG" to "en_US.UTF-8",
        "TERM" to "dumb",
        "CARGO_HOME" to File(home, ".cargo").path,
    )

    /** ينشئ ~/storage/shared كرابط رمزي إلى التخزين المشترك. */
    fun linkSharedStorage(): Boolean {
        val dir = File(home, "storage").apply { mkdirs() }
        val link = File(dir, "shared")
        if (Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS)) return true
        return try {
            Os.symlink(Environment.getExternalStorageDirectory().absolutePath, link.path)
            true
        } catch (e: ErrnoException) {
            false
        }
    }
}
