package dev.rustpad.shell;

/** جسر JNI: الدوال تُنفَّذ في مكتبة Rust (libapp.so). */
public final class Native {
    static { System.loadLibrary("app"); }
    private Native() {}
    public static native String hello();
    public static native String click();
}
