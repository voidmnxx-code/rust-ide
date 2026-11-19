package dev.rustpad

/** غلاف JNI لمكتبة librustpad_ts.so (tree-sitter + قواعد Rust). إن لم تُحمَّل نعود إلى ملوّن regex. */
object TreeSitter {
    @Volatile var available = false
        private set

    init {
        try {
            System.loadLibrary("rustpad_ts")
            available = true
        } catch (_: Throwable) {
        }
    }

    /** ثلاثيات [start, end, class] بوحدات char، أو null عند الفشل. */
    @JvmStatic
    external fun nativeHighlight(text: String): IntArray?
}
