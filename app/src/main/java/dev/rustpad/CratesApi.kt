package dev.rustpad

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class CrateInfo(val name: String, val version: String, val desc: String, val downloads: Long)

/** واجهة crates.io (تحتاج إنترنت) + فهرس المكتبات المحمّلة مسبقاً (بدون إنترنت). */
object CratesApi {
    private const val UA = "RustPad/0.1 (https://github.com/voidmnxx-code/rust-ide)"

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12000; c.readTimeout = 20000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "application/json")
        try {
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    fun search(q: String): List<CrateInfo> {
        val j = JSONObject(get("https://crates.io/api/v1/crates?per_page=25&q=" + URLEncoder.encode(q, "UTF-8")))
        val a = j.getJSONArray("crates")
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            CrateInfo(o.getString("name"),
                o.optString("max_stable_version").ifEmpty { o.optString("newest_version") },
                o.optString("description").replace('\n', ' '), o.optLong("downloads"))
        }
    }

    /** إصدارات غير مسحوبة، الأحدث أولاً. */
    fun versions(name: String): List<String> {
        val a = JSONObject(get("https://crates.io/api/v1/crates/$name/versions?per_page=30")).getJSONArray("versions")
        return (0 until a.length()).map { a.getJSONObject(it) }
            .filter { !it.optBoolean("yanked") && !it.getString("num").contains('-') }
            .map { it.getString("num") }
    }

    /** ميزات الإصدار: الاسم -> true إن كانت ضمن default. */
    fun features(name: String, version: String): Map<String, Boolean> {
        val v = JSONObject(get("https://crates.io/api/v1/crates/$name/$version")).getJSONObject("version")
        val f = v.optJSONObject("features") ?: return emptyMap()
        val defaults = HashSet<String>()
        f.optJSONArray("default")?.let { for (i in 0 until it.length()) defaults.add(it.getString(i)) }
        val res = LinkedHashMap<String, Boolean>()
        for (k in f.keys().asSequence().sorted()) if (k != "default") res[k] = k in defaults
        return res
    }

    /** المكتبات المحمّلة مسبقاً: الاسم -> الإصدارات. */
    fun vendored(env: Env): Map<String, List<String>> {
        val dir = File(env.prefix, "share/rustpad/vendor")
        val res = sortedMapOf<String, MutableList<String>>()
        val nameRx = Regex("(?m)^name\\s*=\\s*\"([^\"]+)\"")
        val verRx = Regex("(?m)^version\\s*=\\s*\"([^\"]+)\"")
        dir.listFiles { f -> f.isDirectory }?.forEach { d ->
            try {
                val t = File(d, "Cargo.toml").readText()
                val n = nameRx.find(t)?.groupValues?.get(1) ?: return@forEach
                val v = verRx.find(t)?.groupValues?.get(1) ?: return@forEach
                res.getOrPut(n) { ArrayList() }.add(v)
            } catch (_: Throwable) {}
        }
        return res
    }
}
