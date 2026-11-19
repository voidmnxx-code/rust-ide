package dev.rustpad.shell;

/** جسر JNI إلى libapp.so (Rust). */
public final class Native {
    static { System.loadLibrary("app"); }
    private Native() {}

    // واجهة عارضة (الإطار الحالي): تعيد JSON يصف الواجهة، أو "" بلا تغيير
    public static native String init();
    public static native String event(String id, String value, String inputs);

    // واجهة قديمة (مشاريع سابقة): تُستخدم فقط إن لم توجد init/event في المكتبة
    public static native String hello();
    public static native String click();
}
