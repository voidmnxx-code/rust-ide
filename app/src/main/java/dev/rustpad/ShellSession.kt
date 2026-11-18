package dev.rustpad

import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** جلسة شل بسيطة (بدون PTY في المرحلة 1). */
class ShellSession(
    private val env: Env,
    private val onOutput: (String) -> Unit,
    private val onExit: (Int) -> Unit,
) {
    private var process: Process? = null
    private var stdin: OutputStream? = null
    private val writer = Executors.newSingleThreadExecutor()

    fun start() {
        env.ensureDirs()
        val pb = ProcessBuilder(env.shellPath())
            .directory(env.home)
            .redirectErrorStream(true)
        pb.environment().apply { clear(); putAll(env.environment()) }
        val p = pb.start()
        process = p
        stdin = p.outputStream
        thread(isDaemon = true, name = "shell-reader") {
            val reader = InputStreamReader(p.inputStream, Charsets.UTF_8)
            val buf = CharArray(4096)
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                onOutput(String(buf, 0, n))
            }
            onExit(p.waitFor())
        }
    }

    fun send(line: String) {
        writer.execute {
            try {
                stdin?.apply { write((line + "\n").toByteArray()); flush() }
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        process?.destroy()
        writer.shutdown()
    }
}
