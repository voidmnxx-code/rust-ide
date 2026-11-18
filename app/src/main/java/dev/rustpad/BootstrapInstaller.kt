package dev.rustpad

import android.system.Os
import com.github.luben.zstd.ZstdInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** يحمّل أرشيف tar.zst ويفكّه داخل $PREFIX (يحافظ على الروابط الرمزية والأذونات). */
class BootstrapInstaller(private val env: Env) {

    fun installStage(stage: Stage, log: (String) -> Unit) {
        env.ensureDirs()
        val archive = File(env.tmp, "${stage.id}.tar.zst")
        try {
            log("[${stage.id}] downloading...")
            download(stage.url, archive, log)
            if (stage.sha256.isNotBlank()) {
                log("[${stage.id}] verifying sha256...")
                val actual = sha256(archive)
                if (!actual.equals(stage.sha256, ignoreCase = true)) {
                    throw IOException("sha256 mismatch: $actual")
                }
            }
            log("[${stage.id}] extracting...")
            FileInputStream(archive).use { extract(it, log) }
            env.markInstalled(stage.id)
            log("[${stage.id}] done.")
        } finally {
            archive.delete()
        }
    }

    /** تثبيت من ملف محلي (استيراد عبر SAF). */
    fun installFromStream(id: String, input: InputStream, log: (String) -> Unit) {
        env.ensureDirs()
        log("[$id] extracting...")
        extract(input, log)
        env.markInstalled(id)
        log("[$id] done.")
    }

    private fun download(url: String, dest: File, log: (String) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} for $url")
            val total = conn.contentLengthLong
            var done = 0L
            var lastPct = -1
            conn.inputStream.use { ins ->
                FileOutputStream(dest).use { outs ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        outs.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct / 10 != lastPct / 10) { lastPct = pct; log("  $pct%") }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun extract(raw: InputStream, log: (String) -> Unit) {
        val root = env.prefix.toPath().toAbsolutePath().normalize()
        var count = 0
        TarArchiveInputStream(ZstdInputStream(BufferedInputStream(raw, 1 shl 16))).use { tar ->
            var e = tar.nextEntry
            while (e != null) {
                val path = root.resolve(e.name).normalize()
                if (path == root) { e = tar.nextEntry; continue }
                if (!path.startsWith(root)) throw IOException("blocked path: ${e.name}")
                val target = path.toFile()
                when {
                    e.isDirectory -> target.mkdirs()
                    e.isSymbolicLink -> {
                        target.parentFile?.mkdirs()
                        target.delete()
                        Os.symlink(e.linkName, target.path)
                    }
                    e.isFile -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { tar.copyTo(it) }
                        val perm = (e.mode and 0x1FF).let { if (it == 0) 0x1A4 else it or 0x80 }
                        Os.chmod(target.path, perm)
                    }
                    else -> log("  skip: ${e.name}")
                }
                if (++count % 500 == 0) log("  $count files")
                e = tar.nextEntry
            }
        }
        log("  $count entries")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
