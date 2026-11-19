package dev.rustpad

/**
 * يجد كتل { } الممتدة على أكثر من سطر. يعيد: بداية السطر -> [موضع '{', موضع '}'].
 * يتجاهل التعليقات والنصوص والمحارف وعمر الحياة. إن تعدّدت الكتل في سطر تُختار آخر '{' فيه.
 */
object FoldScanner {
    private fun ident(c: Char) = c.isLetterOrDigit() || c == '_'

    fun scan(s: String): Map<Int, IntArray> {
        val n = s.length
        val stack = ArrayList<Int>()
        val res = LinkedHashMap<Int, IntArray>()
        var i = 0
        while (i < n) {
            val c = s[i]
            when {
                c == '/' && i + 1 < n && s[i + 1] == '/' -> {
                    while (i < n && s[i] != '\n') i++
                }
                c == '/' && i + 1 < n && s[i + 1] == '*' -> {
                    var depth = 1
                    i += 2
                    while (i < n && depth > 0) {
                        if (s[i] == '/' && i + 1 < n && s[i + 1] == '*') { depth++; i += 2 }
                        else if (s[i] == '*' && i + 1 < n && s[i + 1] == '/') { depth--; i += 2 }
                        else i++
                    }
                }
                c == '"' -> {
                    i++
                    while (i < n && s[i] != '"') { if (s[i] == '\\') i++; i++ }
                    i++
                }
                c == 'r' && (i == 0 || !ident(s[i - 1]) || (s[i - 1] == 'b' && (i < 2 || !ident(s[i - 2])))) &&
                    rawStart(s, i) >= 0 -> {
                    val q = rawStart(s, i)
                    val hashes = q - (i + 1)
                    val end = "\"" + "#".repeat(hashes)
                    val idx = s.indexOf(end, q + 1)
                    i = if (idx < 0) n else idx + end.length
                }
                c == '\'' -> {
                    if (i + 1 < n && s[i + 1] == '\\') {
                        var j = i + 2
                        while (j < n && s[j] != '\'' && s[j] != '\n') j++
                        i = j + 1
                    } else if (i + 2 < n && s[i + 2] == '\'' && s[i + 1] != '\'') i += 3
                    else i++ // عمر حياة
                }
                c == '{' -> { stack.add(i); i++ }
                c == '}' -> {
                    if (stack.isNotEmpty()) {
                        val open = stack.removeAt(stack.size - 1)
                        val nl = s.indexOf('\n', open)
                        if (nl in 0 until i) {
                            val ls = s.lastIndexOf('\n', open) + 1
                            val old = res[ls]
                            if (old == null || old[0] < open) res[ls] = intArrayOf(open, i)
                        }
                    }
                    i++
                }
                else -> i++
            }
        }
        return res
    }

    /** إن كان عند i بداية سلسلة خام r#"..."# يعيد موضع علامة الاقتباس، وإلا -1. */
    private fun rawStart(s: String, i: Int): Int {
        var j = i + 1
        while (j < s.length && s[j] == '#') j++
        return if (j < s.length && s[j] == '"') j else -1
    }
}
