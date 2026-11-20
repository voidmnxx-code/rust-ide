package dev.rustpad

import android.content.Context
import android.content.res.AssetManager
import org.json.JSONObject
import java.io.File

/** تمرين Rustlings (البيانات من assets/learn/rustlings/info.json). */
data class Exercise(val name: String, val dir: String, val test: Boolean, val hint: String) {
    val path get() = "exercises/$dir/$name.rs"
    val solutionPath get() = ".solutions/$dir/$name.rs"
}

/**
 * Rustlings (MIT, https://github.com/rust-lang/rustlings) مضمّن في التطبيق ويعمل بلا إنترنت:
 * ينسخ إلى ~/projects/rustlings مشروع cargo فيه كل تمرين كـ [[bin]].
 */
object Rustlings {
    const val NAME = "rustlings"

    fun projectDir(env: Env) = File(env.home, "projects/$NAME")
    private fun infoFile(env: Env) = File(projectDir(env), ".learn/info.json")
    private fun doneFile(env: Env) = File(projectDir(env), ".learn/done")

    fun isInstalled(env: Env) = infoFile(env).exists()

    /** ينسخ الدورة من assets عند أول استخدام. */
    fun ensure(ctx: Context, env: Env): File {
        val dir = projectDir(env)
        if (!isInstalled(env)) {
            dir.mkdirs()
            val am = ctx.assets
            copyTree(am, "learn/rustlings/exercises", File(dir, "exercises"))
            copyTree(am, "learn/rustlings/solutions", File(dir, ".solutions"))
            copyFile(am, "learn/rustlings/Cargo.toml", File(dir, "Cargo.toml"))
            copyFile(am, "learn/rustlings/LICENSE", File(dir, "LICENSE"))
            File(dir, ".gitignore").writeText("target/\n")
            File(dir, ".learn").mkdirs()
            copyFile(am, "learn/rustlings/info.json", infoFile(env)) // آخر خطوة = علامة التثبيت
        }
        return dir
    }

    private data class Info(val welcome: String, val list: List<Exercise>)

    private fun parse(text: String): Info {
        val o = JSONObject(text)
        val a = o.getJSONArray("exercises")
        val list = (0 until a.length()).map {
            val e = a.getJSONObject(it)
            Exercise(e.getString("name"), e.optString("dir"), e.optBoolean("test", true), e.optString("hint"))
        }
        return Info(o.optString("welcome"), list)
    }

    fun exercises(env: Env): List<Exercise> =
        try { parse(infoFile(env).readText()).list } catch (_: Throwable) { emptyList() }

    fun welcome(env: Env): String =
        try { parse(infoFile(env).readText()).welcome } catch (_: Throwable) { "" }

    /** التمرين المقابل لملف داخل مشروع Rustlings (مسار نسبي) أو null. */
    fun exerciseFor(env: Env, rel: String): Exercise? = exercises(env).firstOrNull { it.path == rel }

    fun done(env: Env): Set<String> =
        try { doneFile(env).readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() } catch (_: Throwable) { emptySet() }

    fun markDone(env: Env, name: String) {
        val d = done(env)
        if (name in d) return
        try { doneFile(env).appendText(name + "\n") } catch (_: Throwable) { }
    }

    fun unmarkDone(env: Env, name: String) {
        try { doneFile(env).writeText(done(env).filter { it != name }.joinToString("\n") + "\n") } catch (_: Throwable) { }
    }

    /** يعيد ملف التمرين إلى حالته الأصلية. */
    fun reset(ctx: Context, env: Env, ex: Exercise) {
        copyFile(ctx.assets, "learn/rustlings/${ex.path}", File(projectDir(env), ex.path))
        unmarkDone(env, ex.name)
    }

    /** أول تمرين غير محلول بعد [after] (أو من البداية). */
    fun nextUnsolved(env: Env, after: Exercise?): Exercise? {
        val all = exercises(env)
        val d = done(env)
        val start = if (after == null) 0 else all.indexOfFirst { it.name == after.name } + 1
        return (all.drop(start) + all.take(start)).firstOrNull { it.name !in d && it.name != after?.name }
    }

    private fun copyTree(am: AssetManager, asset: String, dest: File) {
        val kids = am.list(asset) ?: return
        if (kids.isEmpty()) { copyFile(am, asset, dest); return }
        dest.mkdirs()
        for (k in kids) copyTree(am, "$asset/$k", File(dest, k))
    }

    private fun copyFile(am: AssetManager, asset: String, dest: File) {
        dest.parentFile?.mkdirs()
        am.open(asset).use { ins -> dest.outputStream().use { ins.copyTo(it) } }
    }
}

/** تمارين Exercism لمسار Rust: تُستنسخ من GitHub مرة واحدة ثم تعمل بلا إنترنت. */
object Exercism {
    const val URL = "https://github.com/exercism/rust"
    fun repo(env: Env) = File(env.home, "learn/exercism-rust")
    private fun practice(env: Env) = File(repo(env), "exercises/practice")

    fun isCloned(env: Env) = practice(env).isDirectory

    fun slugs(env: Env): List<String> =
        practice(env).listFiles { f -> f.isDirectory && File(f, "Cargo.toml").exists() }?.map { it.name }?.sorted() ?: emptyList()

    /** ينسخ التمرين إلى ~/projects/<slug> بدون مجلد الحل (.meta). يعيد المجلد أو null. */
    fun install(env: Env, slug: String): File? {
        val src = File(practice(env), slug)
        if (!src.isDirectory) return null
        val dest = File(env.home, "projects/$slug")
        if (dest.exists()) return dest
        src.copyRecursively(dest, overwrite = false) { _, _ -> kotlin.io.OnErrorAction.SKIP }
        File(dest, ".meta").deleteRecursively()
        File(dest, "target").deleteRecursively()
        File(dest, ".gitignore").let { if (!it.exists()) it.writeText("target/\nCargo.lock\n") }
        return dest
    }
}
