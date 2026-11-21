package dev.rustpad

import java.io.File

/** قراءة/كتابة app.properties مع الحفاظ على المفاتيح غير المعروفة. */
class AppProps(private val f: File) {
    private val map = LinkedHashMap<String, String>()
    init {
        if (f.exists()) f.readLines().forEach { l ->
            if ('=' in l && !l.trim().startsWith("#")) map[l.substringBefore('=').trim()] = l.substringAfter('=').trim()
        }
    }
    operator fun get(k: String, def: String = "") = map[k] ?: def
    operator fun set(k: String, v: String) { map[k] = v }
    fun save() { f.writeText(map.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n") }
}
