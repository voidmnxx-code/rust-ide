package dev.rustpad

import android.graphics.Color
import android.text.style.ForegroundColorSpan

/** ملوّن خفيف يعتمد على regex. يُستبدل لاحقاً بـ Tree-sitter عبر JNI دون تغيير الواجهة. */
class HlSpan(color: Int) : ForegroundColorSpan(color)

object RustHighlighter {
    data class Tok(val start: Int, val end: Int, val color: Int)

    private val cKeyword = Color.parseColor("#CF8E6D")
    private val cString = Color.parseColor("#6AAB73")
    private val cComment = Color.parseColor("#7A7E85")
    private val cNumber = Color.parseColor("#2AACB8")
    private val cType = Color.parseColor("#56A8F5")
    private val cMacro = Color.parseColor("#C77DBB")
    private val cAttr = Color.parseColor("#B3AE60")

    private val comment = Regex("//[^\\n]*|/\\*[\\s\\S]*?(?:\\*/|$)")
    private val string = Regex("\"(?:\\\\.|[^\"\\\\])*(?:\"|$)")
    private val attr = Regex("#!?\\[[^\\]\\n]*]")
    private val keyword = Regex(
        "\\b(?:fn|let|mut|pub|struct|enum|impl|trait|use|mod|match|if|else|for|while|loop|return|" +
            "self|Self|crate|super|as|in|ref|move|async|await|const|static|type|where|unsafe|dyn|" +
            "break|continue|true|false)\\b"
    )
    private val macro = Regex("\\b[a-z_][a-z0-9_]*!")
    private val type = Regex("\\b[A-Z][A-Za-z0-9_]*\\b")
    private val number = Regex("\\b\\d[\\d_]*(?:\\.\\d+)?(?:[iu](?:8|16|32|64|128|size)|f32|f64)?\\b")

    private val cFunc = Color.parseColor("#FFC66D")

    /** Tree-sitter إن توفّر (تحليل نحوي حقيقي)، وإلا regex. */
    fun highlight(src: String): List<Tok> {
        if (TreeSitter.available) {
            try {
                val a = TreeSitter.nativeHighlight(src)
                if (a != null) {
                    val out = ArrayList<Tok>(a.size / 3)
                    var i = 0
                    while (i + 2 < a.size) {
                        val c = when (a[i + 2]) {
                            1 -> cKeyword; 2 -> cString; 3 -> cComment; 4 -> cNumber; 5 -> cType
                            6 -> cMacro; 7 -> cAttr; 8 -> cFunc; else -> cAttr
                        }
                        if (a[i] in 0 until a[i + 1] && a[i + 1] <= src.length) out.add(Tok(a[i], a[i + 1], c))
                        i += 3
                    }
                    return out
                }
            } catch (_: Throwable) {
            }
        }
        return tokens(src)
    }

    fun tokens(src: String): List<Tok> {
        val out = ArrayList<Tok>()
        val mask = BooleanArray(src.length)
        fun protect(r: Regex, color: Int) {
            for (m in r.findAll(src)) {
                val s = m.range.first
                if (s < src.length && mask[s]) continue
                val e = m.range.last + 1
                for (i in s until e) mask[i] = true
                out.add(Tok(s, e, color))
            }
        }
        // الترتيب مهم: التعليقات والنصوص أولاً، وما بداخلها لا يُلوَّن مرة أخرى.
        protect(comment, cComment)
        protect(string, cString)
        fun plain(r: Regex, color: Int) {
            for (m in r.findAll(src)) {
                val s = m.range.first
                if (mask[s]) continue
                out.add(Tok(s, m.range.last + 1, color))
            }
        }
        plain(attr, cAttr)
        plain(macro, cMacro)
        plain(keyword, cKeyword)
        plain(type, cType)
        plain(number, cNumber)
        return out
    }
}
