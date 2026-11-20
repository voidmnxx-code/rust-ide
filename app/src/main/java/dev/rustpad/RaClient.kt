package dev.rustpad

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class Loc(val file: String, val line: Int, val col: Int)
class CAction(val title: String, val edit: JSONObject?, val raw: JSONObject)
class TextEdit(val l1: Int, val c1: Int, val l2: Int, val c2: Int, val text: String)

class CItem(val label: String, val detail: String?, val newText: String, val range: IntArray?, val sort: String)

/** عميل LSP بسيط لـ rust-analyzer عبر stdio: مزامنة الملفات، الإكمال، التشخيص. */
class RaClient(
    private val env: Env,
    private val project: File,
    private val onDiagnostics: (relPath: String, diags: List<Diag>) -> Unit,
    private val onState: (String) -> Unit,
) {
    @Volatile var ready = false; private set
    @Volatile var lastUsed = SystemClock.elapsedRealtime(); private set
    val alive: Boolean get() = proc?.isAlive == true

    private var proc: Process? = null
    private var out: OutputStream? = null
    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, (JSONObject) -> Unit>()
    private val versions = HashMap<String, Int>()
    private val queued = ConcurrentHashMap<File, String>()
    private val sender = Executors.newSingleThreadExecutor()
    private val rawDiags = ConcurrentHashMap<String, JSONArray>()

    private fun uri(f: File) = "file://" + f.absolutePath

    fun start(): Boolean {
        val bin = File(env.prefix, "bin/rust-analyzer")
        if (!bin.exists()) { onState("RA: not installed"); return false }
        val pb = ProcessBuilder(bin.path).directory(project)
        pb.environment().apply { clear(); putAll(env.environment()); put("RA_LOG", "error") }
        val p = pb.start()
        proc = p; out = p.outputStream
        thread(isDaemon = true, name = "ra-err") { try { p.errorStream.copyTo(java.io.OutputStream.nullOutputStream()) } catch (_: Throwable) {} }
        thread(isDaemon = true, name = "ra-read") {
            try { readLoop(p.inputStream) } catch (_: Throwable) {}
            ready = false
            onState("RA: stopped")
        }
        onState("RA: starting...")
        val caps = JSONObject()
            .put("textDocument", JSONObject()
                .put("completion", JSONObject().put("completionItem", JSONObject().put("snippetSupport", false)))
                .put("publishDiagnostics", JSONObject())
                .put("definition", JSONObject().put("linkSupport", false))
                .put("references", JSONObject())
                .put("hover", JSONObject().put("contentFormat", JSONArray().put("plaintext")))
                .put("rename", JSONObject().put("prepareSupport", false))
                .put("codeAction", JSONObject()
                    .put("codeActionLiteralSupport", JSONObject().put("codeActionKind",
                        JSONObject().put("valueSet", JSONArray().put("quickfix").put("refactor").put("source"))))
                    .put("resolveSupport", JSONObject().put("properties", JSONArray().put("edit")))))
            .put("workspace", JSONObject().put("configuration", true)
                .put("workspaceEdit", JSONObject().put("documentChanges", false)))
        val params = JSONObject()
            .put("processId", android.os.Process.myPid())
            .put("rootUri", uri(project))
            .put("capabilities", caps)
            .put("workspaceFolders", JSONArray().put(JSONObject().put("uri", uri(project)).put("name", project.name)))
            .put("initializationOptions", JSONObject(OPTIONS))
        request("initialize", params) {
            notify("initialized", JSONObject())
            ready = true
            onState("RA: ready")
            for ((f, t) in queued) { queued.remove(f); doSync(f, t) }
        }
        return true
    }

    fun stop() {
        ready = false
        try { proc?.destroy() } catch (_: Throwable) {}
        sender.shutdown()
    }

    fun sync(file: File, text: String) {
        lastUsed = SystemClock.elapsedRealtime()
        if (!ready) { queued[file] = text; return }
        doSync(file, text)
    }

    @Synchronized private fun doSync(file: File, text: String) {
        val u = uri(file)
        val v = (versions[u] ?: 0) + 1
        versions[u] = v
        if (v == 1) {
            notify("textDocument/didOpen", JSONObject().put("textDocument",
                JSONObject().put("uri", u).put("languageId", "rust").put("version", v).put("text", text)))
        } else {
            notify("textDocument/didChange", JSONObject()
                .put("textDocument", JSONObject().put("uri", u).put("version", v))
                .put("contentChanges", JSONArray().put(JSONObject().put("text", text))))
        }
    }

    fun completion(file: File, line: Int, col: Int, cb: (List<CItem>) -> Unit) {
        lastUsed = SystemClock.elapsedRealtime()
        val params = JSONObject()
            .put("textDocument", JSONObject().put("uri", uri(file)))
            .put("position", JSONObject().put("line", line).put("character", col))
        request("textDocument/completion", params) { m ->
            val r = m.opt("result")
            val arr = (when (r) { is JSONArray -> r; is JSONObject -> r.optJSONArray("items"); else -> null }) ?: JSONArray()
            val items = ArrayList<CItem>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val label = o.optString("label")
                val te = o.optJSONObject("textEdit")
                val text = te?.optString("newText") ?: o.optString("insertText", label)
                val rg = te?.let { it.optJSONObject("range") ?: it.optJSONObject("replace") ?: it.optJSONObject("insert") }
                val range = rg?.let {
                    val s = it.getJSONObject("start"); val e = it.getJSONObject("end")
                    intArrayOf(s.getInt("line"), s.getInt("character"), e.getInt("line"), e.getInt("character"))
                }
                items.add(CItem(label, o.optString("detail").ifEmpty { null }, text, range, o.optString("sortText", label)))
            }
            items.sortBy { it.sort }
            cb(items.take(60))
        }
    }

    // ---------- تنقّل وتحرير ----------
    private fun at(file: File, line: Int, col: Int) = JSONObject()
        .put("textDocument", JSONObject().put("uri", uri(file)))
        .put("position", JSONObject().put("line", line).put("character", col))

    private fun parseLocs(r: Any?): List<Loc> {
        val arr = when (r) { is JSONArray -> r; is JSONObject -> JSONArray().put(r); else -> return emptyList() }
        val res = ArrayList<Loc>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val u = o.optString("uri").ifEmpty { o.optString("targetUri") }
            val rg = o.optJSONObject("range") ?: o.optJSONObject("targetSelectionRange") ?: continue
            val st = rg.getJSONObject("start")
            if (u.startsWith("file://")) res.add(Loc(u.removePrefix("file://"), st.getInt("line"), st.getInt("character")))
        }
        return res
    }

    fun definition(file: File, line: Int, col: Int, cb: (List<Loc>) -> Unit) {
        lastUsed = SystemClock.elapsedRealtime()
        request("textDocument/definition", at(file, line, col)) { m -> cb(parseLocs(m.opt("result"))) }
    }

    fun references(file: File, line: Int, col: Int, cb: (List<Loc>) -> Unit) {
        lastUsed = SystemClock.elapsedRealtime()
        val p = at(file, line, col).put("context", JSONObject().put("includeDeclaration", true))
        request("textDocument/references", p) { m -> cb(parseLocs(m.opt("result"))) }
    }

    private fun markup(c: Any?): String? = when (c) {
        is String -> c.ifEmpty { null }
        is JSONObject -> c.optString("value").ifEmpty { null }
        is JSONArray -> (0 until c.length()).mapNotNull { markup(c.opt(it)) }.joinToString("\n\n").ifEmpty { null }
        else -> null
    }

    fun hover(file: File, line: Int, col: Int, cb: (String?) -> Unit) {
        lastUsed = SystemClock.elapsedRealtime()
        request("textDocument/hover", at(file, line, col)) { m -> cb(m.optJSONObject("result")?.let { markup(it.opt("contents")) }) }
    }

    /** يعيد WorkspaceEdit أو رسالة خطأ. */
    fun rename(file: File, line: Int, col: Int, newName: String, cb: (JSONObject?, String?) -> Unit) {
        lastUsed = SystemClock.elapsedRealtime()
        request("textDocument/rename", at(file, line, col).put("newName", newName)) { m ->
            val err = m.optJSONObject("error")
            if (err != null) cb(null, err.optString("message", "rename failed")) else cb(m.optJSONObject("result"), null)
        }
    }

    fun codeActions(file: File, l1: Int, c1: Int, l2: Int, c2: Int, cb: (List<CAction>) -> Unit) {
        lastUsed = SystemClock.elapsedRealtime()
        val diags = JSONArray()
        rawDiags[file.absolutePath]?.let { a ->
            for (i in 0 until a.length()) {
                val d = a.optJSONObject(i) ?: continue
                val st = d.optJSONObject("range")?.optJSONObject("start")?.optInt("line", -1) ?: -1
                if (st in l1..l2) diags.put(d)
            }
        }
        val p = JSONObject().put("textDocument", JSONObject().put("uri", uri(file)))
            .put("range", JSONObject()
                .put("start", JSONObject().put("line", l1).put("character", c1))
                .put("end", JSONObject().put("line", l2).put("character", c2)))
            .put("context", JSONObject().put("diagnostics", diags))
        request("textDocument/codeAction", p) { m ->
            val arr = m.optJSONArray("result") ?: JSONArray()
            val res = ArrayList<CAction>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.opt("command") is String) continue // أمر وليس CodeAction
                val t = o.optString("title"); if (t.isEmpty()) continue
                res.add(CAction(t, o.optJSONObject("edit"), o))
            }
            cb(res)
        }
    }

    fun resolveEdit(a: CAction, cb: (JSONObject?) -> Unit) {
        if (a.edit != null) { cb(a.edit); return }
        request("codeAction/resolve", a.raw) { m -> cb(m.optJSONObject("result")?.optJSONObject("edit")) }
    }

    // ---------- JSON-RPC ----------
    private fun request(method: String, params: JSONObject, cb: (JSONObject) -> Unit) {
        val id = ids.getAndIncrement()
        pending[id] = cb
        send(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params))
    }

    private fun notify(method: String, params: JSONObject) =
        send(JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params))

    private fun send(j: JSONObject) {
        val body = j.toString().toByteArray(Charsets.UTF_8)
        try {
            sender.execute {
                try {
                    out?.apply {
                        write("Content-Length: ${body.size}\r\n\r\n".toByteArray())
                        write(body); flush()
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun readLoop(ins: InputStream) {
        val inp = BufferedInputStream(ins)
        while (true) {
            var len = -1
            while (true) {
                val line = headerLine(inp) ?: return
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) len = line.substring(15).trim().toInt()
            }
            if (len < 0) continue
            val buf = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = inp.read(buf, off, len - off)
                if (n < 0) return
                off += n
            }
            try { handle(JSONObject(String(buf, Charsets.UTF_8))) } catch (_: Throwable) {}
        }
    }

    private fun headerLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0) return null
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun handle(m: JSONObject) {
        val method = m.optString("method", "")
        if (method.isEmpty()) { pending.remove(m.optInt("id", -1))?.invoke(m); return }
        if (m.has("id")) { // طلب من الخادم: نردّ بقيم فارغة
            val result: Any = if (method == "workspace/configuration") {
                JSONArray().also { a ->
                    repeat(m.optJSONObject("params")?.optJSONArray("items")?.length() ?: 0) { a.put(JSONObject.NULL) }
                }
            } else JSONObject.NULL
            send(JSONObject().put("jsonrpc", "2.0").put("id", m.get("id")).put("result", result))
            return
        }
        if (method == "textDocument/publishDiagnostics") {
            val p = m.getJSONObject("params")
            val path = p.getString("uri").removePrefix("file://")
            val rel = File(path).relativeToOrNull(project)?.path ?: return
            val arr = p.getJSONArray("diagnostics")
            rawDiags[path] = arr
            val list = ArrayList<Diag>()
            for (i in 0 until arr.length()) {
                val d = arr.getJSONObject(i)
                val sev = d.optInt("severity", 1)
                if (sev > 2) continue
                val st = d.getJSONObject("range").getJSONObject("start")
                list.add(Diag(if (sev == 1) "error" else "warning", d.optString("message"), rel,
                    st.getInt("line") + 1, st.getInt("character") + 1))
            }
            onDiagnostics(rel, list)
        }
    }

    companion object {
        // إعدادات خفيفة: بلا checkOnSave (لا نرسل didSave أصلاً)، بلا ماكروز/سكربتات بناء، بلا تحميل مسبق.
        private const val OPTIONS =
            """{"checkOnSave":false,"cachePriming":{"enable":false},"cargo":{"buildScripts":{"enable":false}},""" +
            """"procMacro":{"enable":false},"lens":{"enable":false},"hover":{"actions":{"enable":false}},""" +
            """"files":{"watcher":"server"}}"""
    }
}
