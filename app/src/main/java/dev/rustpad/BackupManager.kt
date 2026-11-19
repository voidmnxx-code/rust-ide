package dev.rustpad

import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * نسخ احتياطي للمشاريع (بدون target/ و dist/):
 *  - داخلي: ~/.rustpad/backups/<مشروع>/<وقت>.zip
 *  - خارجي (يبقى بعد حذف التطبيق): <التخزين المشترك>/RustPadBackup/<مشروع>/<وقت>.zip إن مُنح الإذن
 * يحتفظ بآخر KEEP نسخ لكل مشروع في كل موقع.
 */
class BackupManager(private val env: Env) {
    companion object { const val KEEP = 5 }

    private val internalRoot get() = File(env.home, ".rustpad/backups")
    val externalRoot: File get() = File(Environment.getExternalStorageDirectory(), "RustPadBackup")

    private fun skip(rel: String): Boolean {
        val top = rel.substringBefore('/')
        return top == "target" || top == "dist"
    }

    private fun lastModified(dir: File): Long =
        dir.walkTopDown().onEnter { !skip(it.relativeTo(dir).path) && it.name != "target" }
            .filter { it.isFile }.maxOfOrNull { it.lastModified() } ?: 0L

    /** هل تغيّر المشروع منذ آخر نسخة داخلية؟ */
    fun needsBackup(project: File): Boolean {
        val last = File(internalRoot, project.name).listFiles { f -> f.extension == "zip" }
            ?.maxOfOrNull { it.lastModified() } ?: 0L
        return lastModified(project) > last
    }

    /** ينسخ المشروع. يعيد وصفاً قصيراً للنتيجة. */
    fun backup(project: File, external: Boolean): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = StringBuilder()
        val targets = ArrayList<File>()
        targets.add(File(internalRoot, project.name))
        if (external) targets.add(File(externalRoot, project.name))
        for (dir in targets) {
            try {
                dir.mkdirs()
                val tmp = File(dir, "$stamp.zip.tmp")
                zip(project, tmp)
                tmp.renameTo(File(dir, "$stamp.zip"))
                dir.listFiles { f -> f.extension == "zip" }?.sortedByDescending { it.name }
                    ?.drop(KEEP)?.forEach { it.delete() }
                out.append(if (dir.path.startsWith(internalRoot.path)) "internal ok" else "shared ok").append("; ")
            } catch (t: Throwable) {
                out.append("${if (dir.path.startsWith(internalRoot.path)) "internal" else "shared"} failed: ${t.message}; ")
            }
        }
        return out.toString().trim()
    }

    private fun zip(project: File, dest: File) {
        ZipOutputStream(dest.outputStream().buffered()).use { z ->
            project.walkTopDown().onEnter { !skip(it.relativeTo(project).path) }.forEach { f ->
                val rel = f.relativeTo(project).path
                if (rel.isEmpty() || skip(rel)) return@forEach
                if (f.isDirectory) { z.putNextEntry(ZipEntry("$rel/")); z.closeEntry() }
                else if (f.isFile) {
                    z.putNextEntry(ZipEntry(rel))
                    f.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                }
            }
        }
    }

    class Entry(val project: String, val file: File)

    /** أحدث نسخة لكل مشروع من المجلد الخارجي ثم الداخلي (الخارجي أولاً). */
    fun available(): List<Entry> {
        val res = LinkedHashMap<String, Entry>()
        for (root in listOf(externalRoot, internalRoot)) {
            root.listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.forEach { d ->
                val newest = d.listFiles { f -> f.extension == "zip" }?.maxByOrNull { it.name }
                if (newest != null) {
                    val old = res[d.name]
                    if (old == null || newest.name > old.file.name) res[d.name] = Entry(d.name, newest)
                }
            }
        }
        return res.values.toList()
    }

    /** يفك النسخة في ~/projects/<اسم> (أو <اسم>-restored إن وُجد). يعيد المجلد أو null. */
    fun restore(e: Entry, projectsRoot: File): File? {
        var dir = File(projectsRoot, e.project)
        if (dir.exists()) dir = File(projectsRoot, e.project + "-restored")
        if (dir.exists()) return null
        dir.mkdirs()
        val base = dir.canonicalPath + File.separator
        ZipInputStream(e.file.inputStream().buffered()).use { z ->
            while (true) {
                val en = z.nextEntry ?: break
                val out = File(dir, en.name)
                if (!out.canonicalPath.startsWith(base)) continue // حماية من ../
                if (en.isDirectory) out.mkdirs()
                else { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) } }
            }
        }
        return dir
    }
}
